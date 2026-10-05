package com.grassland.intelligence.mediaplatform.segments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.mediaplatform.MediaProcessRunner;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.StoredObject;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class SharedSegmentRendererTest {
    @TempDir java.nio.file.Path temp;
    static class Store {
        final Map<String,byte[]> objects=new ConcurrentHashMap<>();
        final ObjectStorageAdapter storage=mock(ObjectStorageAdapter.class);
        final ObjectProvider<ObjectStorageAdapter> provider=mock(ObjectProvider.class);
        Store() {
            when(provider.getIfAvailable()).thenReturn(storage);
            doAnswer(i -> { objects.put(i.getArgument(0),((byte[])i.getArgument(1)).clone()); return null; }).when(storage).putObject(anyString(),any(),anyString());
            when(storage.getObject(anyString())).thenAnswer(i -> { byte[] data=objects.get(i.getArgument(0)); if(data==null)throw new IllegalStateException("missing");return data.clone(); });
            when(storage.headObject(anyString())).thenAnswer(i -> { String key=i.getArgument(0); byte[] data=objects.get(key);return data==null?Optional.empty():Optional.of(new StoredObject(key,data.length,"application/octet-stream","",Instant.now())); });
            when(storage.listObjects(anyString())).thenAnswer(i -> objects.entrySet().stream().filter(e -> e.getKey().startsWith(i.getArgument(0))).map(e -> new StoredObject(e.getKey(),e.getValue().length,"application/octet-stream","",Instant.now())).toList());
            doAnswer(i -> { objects.remove(i.getArgument(0)); return null; }).when(storage).deleteObject(anyString());
        }
    }
    @Test void realRenderReusesAcrossInstancesAndScopesOwners() throws Exception {
        Store store=new Store(); var env=new MockEnvironment(); var cache=new SharedSegmentCache(store.provider);
        var runner=new MediaProcessRunner(env);
        runner.ffmpeg(List.of("-y","-v","error","-f","lavfi","-i","color=red:s=64x64:r=12:d=1","-c:v","libx264","-threads","1","source.mp4"),java.time.Duration.ofSeconds(20),temp);
        byte[] source=Files.readAllBytes(temp.resolve("source.mp4"));
        var spec=new SegmentSpec("video",64,64,12,1,6,2,"frames","fill",23);
        var first=new SharedSegmentRenderer(runner,cache,env).render("owner-a",source,spec);
        assertFalse(first.cacheHit());
        // Fresh service instance represents a different work / process restart.
        var second=new SharedSegmentRenderer(runner,new SharedSegmentCache(store.provider),env).render("owner-a",source,spec);
        assertTrue(second.cacheHit()); assertArrayEquals(first.bytes(),second.bytes());
        assertFalse(new SharedSegmentRenderer(runner,cache,env).render("owner-b",source,spec).cacheHit());
        assertFalse(new SharedSegmentRenderer(runner,cache,env).render("owner-a",source,new SegmentSpec("video",64,64,12,1,6,3,"frames","fill",23)).cacheHit());
    }
    @Test void fingerprintsInvalidateEveryRenderingInputAndNeverIncludeWorkIds() {
        var cache=new SharedSegmentCache(new Store().provider);
        var base=new SegmentSpec("video",64,64,30,1,30,0,"frames","fill",23);
        String key=cache.fingerprint(new byte[]{1},base,"ffmpeg-v1");
        for(var spec:List.of(new SegmentSpec("video",128,64,30,1,30,0,"frames","fill",23),
                new SegmentSpec("video",64,64,24,1,30,0,"frames","fill",23),new SegmentSpec("video",64,64,30,1,31,0,"frames","fill",23),
                new SegmentSpec("video",64,64,30,1,30,1,"frames","fill",23),new SegmentSpec("video",64,64,30,1,30,0,"frames","contain",23),
                new SegmentSpec("video",64,64,30,1,30,0,"frames","fill",18))) assertNotEquals(key,cache.fingerprint(new byte[]{1},spec,"ffmpeg-v1"));
        assertNotEquals(key,cache.fingerprint(new byte[]{2},base,"ffmpeg-v1"));
        assertNotEquals(key,cache.fingerprint(new byte[]{1},base,"ffmpeg-v2"));
    }
    @Test void corruptExpiredOrUnavailableEntriesAreMisses() {
        Store store=new Store(); var cache=new SharedSegmentCache(store.provider);
        cache.write("a","fingerprint",new byte[]{1,2,3}); assertArrayEquals(new byte[]{1,2,3},cache.read("a","fingerprint"));
        assertNull(cache.read("b","fingerprint"));
        String key=store.objects.keySet().iterator().next(); store.objects.get(key)[40]=99; assertNull(cache.read("a","fingerprint"));
        cache.write("a","fingerprint",new byte[]{1,2,3}); java.nio.ByteBuffer.wrap(store.objects.get(key)).putLong(0); assertNull(cache.read("a","fingerprint")); assertTrue(store.objects.isEmpty());
        doThrow(new IllegalStateException("offline")).when(store.storage).putObject(anyString(),any(),anyString());
        assertDoesNotThrow(() -> cache.write("a","fingerprint",new byte[]{1}));
    }
    @Test void invalidContractAndCancellationFailBeforeWork() throws Exception {
        assertThrows(IllegalArgumentException.class,() -> new SegmentSpec("video",1,64,30,1,30,0,"frames","fill",23));
        Store store=new Store(); var env=new MockEnvironment();
        var renderer=new SharedSegmentRenderer(new MediaProcessRunner(env),new SharedSegmentCache(store.provider),env);
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class,() -> renderer.render("a",new byte[]{1},new SegmentSpec("video",64,64,30,1,30,0,"frames","fill",23))); }
        finally { Thread.interrupted(); }
        assertTrue(store.objects.isEmpty());
    }
}
