package com.grassland.intelligence.mediaplatform.segments;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.hypit.api.HypitMediaSegmentController;
import com.grassland.intelligence.hypit.api.HypitExceptionHandler;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.mediaplatform.MediaProcessRunner;
import com.grassland.storage.*;
import io.r2dbc.spi.ConnectionFactories;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.http.server.HttpServer;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Real HTTP + PostgreSQL + S3 + FFmpeg. Containers serial, scoped and closed. */
class SharedSegmentBridgeIT {
    @Configuration @EnableWebFlux static class Web { }
    @TempDir java.nio.file.Path temp;
    @Test void realBridgeSharesOnlyWithinAccountAndSurvivesRendererRestart() throws Exception {
        String token=UUID.randomUUID().toString()+UUID.randomUUID();
        try (var pg=new PostgreSQLContainer<>("postgres:16-alpine");
             var minio=new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
                     .withEnv("MINIO_ROOT_USER","segment-test").withEnv("MINIO_ROOT_PASSWORD","segment-test-password")
                     .withCommand("server /data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))) {
            pg.start(); minio.start();
            var factory=ConnectionFactories.get("r2dbc:postgresql://"+pg.getUsername()+":"+pg.getPassword()+"@"+pg.getHost()+":"+pg.getMappedPort(5432)+"/"+pg.getDatabaseName());
            var db=DatabaseClient.create(factory);
            for(String ddl:List.of("CREATE TABLE hypit_project(id uuid PRIMARY KEY,account_id text,status text)",
                    "CREATE TABLE hypit_build(command_id uuid PRIMARY KEY,project_id uuid,engine_build_id text UNIQUE,lifecycle text)",
                    "CREATE TABLE hypit_job(command_id uuid,cancel_requested_at timestamptz)")) db.sql(ddl).fetch().rowsUpdated().block();
            URI endpoint=URI.create("http://"+minio.getHost()+":"+minio.getMappedPort(9000));
            var credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create("segment-test","segment-test-password"));
            try(var s3=S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials).forcePathStyle(true).build();
                var signer=S3Presigner.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials).build();
                var context=new AnnotationConfigApplicationContext()) {
                s3.createBucket(b->b.bucket("segments"));
                var storage=new S3ObjectStorageAdapter(new ObjectStorageProperties(true,endpoint,endpoint,"us-east-1","segment-test","segment-test-password","segments",true,false),s3,signer);
                context.registerBean(ObjectStorageAdapter.class,()->storage); context.register(Web.class);
                var env=new MockEnvironment(); var runner=new MediaProcessRunner(env);
                context.registerBean(SharedSegmentCache.class,()->new SharedSegmentCache(context.getBeanProvider(ObjectStorageAdapter.class)));
                context.registerBean(SharedSegmentRenderer.class,()->new SharedSegmentRenderer(runner,context.getBean(SharedSegmentCache.class),env));
                context.registerBean(HypitMediaSegmentController.class,()->new HypitMediaSegmentController(new HypitProperties(true,null,token,null,null,null),db,context.getBean(SharedSegmentRenderer.class)));
                context.registerBean(HypitExceptionHandler.class); context.refresh();
                var server=HttpServer.create().host("127.0.0.1").port(0).handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build())).bindNow();
                try {
                    String base="http://127.0.0.1:"+server.port(); var client=HttpClient.newHttpClient();
                    runner.ffmpeg(List.of("-y","-v","error","-f","lavfi","-i","color=red:s=64x64:r=12:d=1","-c:v","libx264","-threads","1","source.mp4"),Duration.ofSeconds(30),temp);
                    byte[] source=Files.readAllBytes(temp.resolve("source.mp4"));
                    String spec=new ObjectMapper().writeValueAsString(new SegmentSpec("video",64,64,12,1,6,2,"frames","fill",23));
                    List<UUID> projects=new ArrayList<>(); List<UUID> commands=new ArrayList<>();
                    for(int i=0;i<3;i++) {
                        UUID project=UUID.randomUUID(),command=UUID.randomUUID();projects.add(project);commands.add(command);
                        db.sql("INSERT INTO hypit_project VALUES(:p,:a,'ready')").bind("p",project).bind("a",i==2?"other":"owner").fetch().rowsUpdated().block();
                        db.sql("INSERT INTO hypit_build VALUES(:c,:p,NULL,'submitting')").bind("c",command).bind("p",project).fetch().rowsUpdated().block();
                        String bind=new ObjectMapper().writeValueAsString(Map.of("commandId",command.toString(),"engineBuildId","native-"+i));
                        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/internal/hypit/media-segments/bind")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(bind)).build(),HttpResponse.BodyHandlers.ofByteArray());
                        assertEquals(204,response.statusCode());
                    }
                    assertEquals(401,request(client,base,"native-0","wrong",spec,source).statusCode());
                    var first=request(client,base,"native-0",token,spec,source);assertEquals(200,first.statusCode(),new String(first.body()));assertEquals("miss",first.headers().firstValue("X-Segment-Cache").orElseThrow());
                    var second=request(client,base,"native-1",token,spec,source);assertEquals(200,second.statusCode());assertEquals("hit",second.headers().firstValue("X-Segment-Cache").orElseThrow());assertArrayEquals(first.body(),second.body());
                    var other=request(client,base,"native-2",token,spec,source);assertEquals(200,other.statusCode());assertEquals("miss",other.headers().firstValue("X-Segment-Cache").orElseThrow());
                    var restarted=new SharedSegmentRenderer(runner,new SharedSegmentCache(context.getBeanProvider(ObjectStorageAdapter.class)),env);
                    assertTrue(restarted.render("owner",source,new ObjectMapper().readValue(spec,SegmentSpec.class)).cacheHit());
                    db.sql("INSERT INTO hypit_job VALUES(:c,now())").bind("c",commands.get(0)).fetch().rowsUpdated().block();assertEquals(404,request(client,base,"native-0",token,spec,source).statusCode());
                    db.sql("UPDATE hypit_project SET status='deleted' WHERE id=:p").bind("p",projects.get(1)).fetch().rowsUpdated().block();assertEquals(404,request(client,base,"native-1",token,spec,source).statusCode());
                    assertEquals(404,request(client,base,"unknown",token,spec,source).statusCode());
                    assertEquals(2,storage.listObjects(SharedSegmentCache.PREFIX).size());
                } finally { server.disposeNow(); }
            }
        }
    }
    private HttpResponse<byte[]> request(HttpClient client,String base,String build,String token,String spec,byte[] source)throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base+"/internal/hypit/media-segments/"+build)).timeout(Duration.ofSeconds(45))
                .header("Authorization","Bearer "+token).header("Content-Type","application/octet-stream").header("X-Segment-Spec",spec)
                .POST(HttpRequest.BodyPublishers.ofByteArray(source)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
}
