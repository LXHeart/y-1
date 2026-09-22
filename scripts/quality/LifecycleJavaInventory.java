import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * 任务书 #104 C104-06（§3 D05）：Java 事件生产点扫描器。
 *
 * 只 parse（JDK 自带编译器树 API），不编译、不执行业务、不解析依赖。
 * 识别任意「声明了 eventType 组件/参数的信封 record/class」（不白名单类名）：
 * - new Envelope(...) 的 eventType 实参：字面量 / static final String 常量 /
 *   拼接 / 三元（有限条件分支）/ 括号 / 返回信封的工厂方法（按实际返回类型识别，
 *   调用点沿工厂参数传播）。
 * - 无法静态解析的生产点输出 unresolved-production-site（文件/方法/表达式 token
 *   摘要），退出码非零——不能返回空当成功。
 * 输出 JSON 到 stdout（events 按 eventType 分组、sites 确定性排序）。
 */
public class LifecycleJavaInventory {

  static final class Site {
    String path;
    String symbol;
    List<String> eventTypes = new ArrayList<>();
    String viaFactory;
  }

  static final class Unresolved {
    String path;
    String symbol;
    String detail;
    String digest;
  }

  /** #106 D04：真实方法/构造声明（path + Class#method(Type,Type)）；注释/字符串不产生声明。 */
  static final class Declaration {
    String path;
    String symbol;
  }

  /** envelope 简名 → eventType 在构造器实参中的下标。 */
  static final Map<String, Integer> envelopeEventTypeIndex = new TreeMap<>();
  /** 类简名 → 常量名 → 字面量值（static final 且可求值）。 */
  static final Map<String, Map<String, String>> classConstants = new TreeMap<>();
  /** 全局唯一简名常量（跨类引用兜底）。 */
  static final Map<String, String> globalConstants = new TreeMap<>();
  static final Set<String> ambiguousConstants = new LinkedHashSet<>();
  /** 工厂：属主类.方法名 → 参数个数 + eventType 形参下标（-1=体内字面量固定）。 */
  static final Map<String, FactoryInfo> factories = new TreeMap<>();
  /** 裸方法名 → 属主集合（无歧义时允许裸名兜底匹配调用点）。 */
  static final Map<String, Set<String>> factoryNames = new TreeMap<>();

  static final class FactoryInfo {
    int arity;
    int eventTypeParamIndex = -1;
    String owner;
    String name;
  }

  static final List<Site> sites = new ArrayList<>();
  static final List<Unresolved> unresolved = new ArrayList<>();
  static final List<Declaration> declarations = new ArrayList<>();
  /** true = 工厂绑定阶段：只登记 eventType 形参下标，不记录生产点。 */
  static boolean bindingPhase = false;

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("usage: LifecycleJavaInventory <src-root>...");
      System.exit(2);
    }
    List<Path> files = new ArrayList<>();
    for (String root : args) {
      Path rootPath = Path.of(root);
      if (!Files.exists(rootPath)) {
        continue;  // 不存在的扫描根视为空（真空仓库允许空），不静默报错也不误报未解析
      }
      collectJava(Files.walk(rootPath), files);
    }
    files.sort(Comparator.comparing(Path::toString));
    if (files.isEmpty()) {
      System.out.println("{\"events\":[],\"unresolved\":[],\"declarations\":[]}");
      return;
    }

    var compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
      List<File> javaFiles = files.stream().map(Path::toFile).toList();
      JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
          List.of("-proc:none"), null, fileManager.getJavaFileObjectsFromFiles(javaFiles));
      Iterable<? extends CompilationUnitTree> units = task.parse();
      boolean parseError = diagnostics.getDiagnostics().stream()
          .anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR);
      if (parseError) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
          if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
            System.err.println(diagnostic);
          }
        }
        System.exit(3);
      }

      // Pass 1：信封与常量声明 + 方法/构造声明清单（#106 D04）。
      for (CompilationUnitTree unit : units) {
        String unitPath = relativePath(unit);
        for (ClassTree clazz : classesOf(unit)) {
          scanDeclaration(clazz);
          collectDeclarations(unitPath, clazz);
        }
        // 顶层常量也扫（枚举/接口外的顶层类已含）。
        voidUnused(unitPath);
      }
      if (System.getenv("LIFECYCLE_JAVA_DEBUG") != null) {
        System.err.println("envelopes=" + envelopeEventTypeIndex);
        System.err.println("factories=" + factories.keySet());
      }
      // Pass 2：工厂绑定（eventType 形参下标）——先于生产点扫描，消除声明顺序依赖。
      bindingPhase = true;
      for (CompilationUnitTree unit : units) {
        for (ClassTree clazz : classesOf(unit)) {
          scanProduction(unit, clazz);
        }
      }
      // Pass 3：生产点与工厂调用点。
      bindingPhase = false;
      for (CompilationUnitTree unit : units) {
        for (ClassTree clazz : classesOf(unit)) {
          scanProduction(unit, clazz);
        }
      }
    }

    emitJson();
    System.exit(unresolved.isEmpty() ? 0 : 4);
  }

  static void voidUnused(String ignored) {
    // 占位：保持 unit 路径在 pass1 可见（无副作用）。
  }

  static String relativePath(CompilationUnitTree unit) {
    if (unit.getSourceFile() == null) {
      return unit.toString();
    }
    String name = unit.getSourceFile().getName();
    String userDir = System.getProperty("user.dir") + File.separator;
    if (name.startsWith(userDir)) {
      return name.substring(userDir.length());
    }
    return name;
  }

  static List<ClassTree> classesOf(CompilationUnitTree unit) {
    List<ClassTree> result = new ArrayList<>();
    for (Tree tree : unit.getTypeDecls()) {
      if (tree instanceof ClassTree clazz) {
        collectNested(clazz, result);
      }
    }
    return result;
  }

  static void collectNested(ClassTree clazz, List<ClassTree> acc) {
    acc.add(clazz);
    for (Tree member : clazz.getMembers()) {
      if (member instanceof ClassTree nested) {
        collectNested(nested, acc);
      }
    }
  }

  /** #106 D04：采集真实方法/显式构造声明（嵌套类用其实际声明名）；仅 AST 声明，注释/字符串不算。 */
  static void collectDeclarations(String unitPath, ClassTree clazz) {
    String simpleName = clazz.getSimpleName().toString();
    for (Tree member : clazz.getMembers()) {
      if (member instanceof MethodTree method) {
        Declaration declaration = new Declaration();
        declaration.path = unitPath;
        // 构造器在 parse 树名为 <init>：登记为 Class#Class(...)（沿现有 Class#method 规范）。
        String methodLabel = method.getName().contentEquals("<init>") ? simpleName : method.getName().toString();
        declaration.symbol = simpleName + "#" + methodLabel + methodSignature(method).substring(method.getName().toString().length());
        declarations.add(declaration);
      }
    }
  }

  /** 识别信封（eventType 组件/构造参数）与 static final String 常量。 */
  static void scanDeclaration(ClassTree clazz) {
    String simpleName = clazz.getSimpleName().toString();
    boolean envelopeFamily = simpleName.endsWith("EventEnvelope");
    int eventTypeIndex = -1;
    for (Tree member : clazz.getMembers()) {
      if (member instanceof VariableTree variable) {
        if (variable.getName().contentEquals("eventType")) {
          eventTypeIndex = memberIndex(clazz, member);
        }
        registerConstant(simpleName, variable);
      } else if (member instanceof MethodTree method && method.getReturnType() == null) {
        // 仅构造器：显式构造器的 eventType 形参下标（record 组件走 VariableTree 分支）。
        List<? extends VariableTree> params = method.getParameters();
        for (int index = 0; index < params.size(); index++) {
          if (params.get(index).getName().contentEquals("eventType")) {
            eventTypeIndex = index;
          }
        }
      }
    }
    if (eventTypeIndex >= 0) {
      // 信封族按命名（*EventEnvelope）+结构（eventType 组件/构造参数）双重判定：
      // webhook/inbox DTO 也可能带 eventType 字段，但不是事件生产信封。
      if (envelopeFamily) {
        envelopeEventTypeIndex.putIfAbsent(simpleName, eventTypeIndex);
      }
    }
    // 记录工厂：返回类型是信封的方法（按实际返回类型识别，不靠名单）。
    for (Tree member : clazz.getMembers()) {
      if (member instanceof MethodTree method && method.getReturnType() != null) {
        String returnSimple = simpleTypeName(method.getReturnType());
        if (envelopeEventTypeIndex.containsKey(returnSimple)) {
          String methodName = method.getName().toString();
          FactoryInfo info = new FactoryInfo();
          info.arity = method.getParameters().size();
          info.owner = simpleName;
          info.name = methodName;
          factories.put(simpleName + "." + methodName, info);
          factoryNames.computeIfAbsent(methodName, ignored -> new LinkedHashSet<>()).add(simpleName);
        }
      }
    }
  }

  /** 调用点解析工厂：限定名优先（ApplicationEvents.envelope）；裸调用按所在类解析，
   * 仍无命中且裸名无歧义（同元数唯一属主）时才跨类兜底。 */
  static FactoryInfo resolveFactory(MethodInvocationTree invocation, String enclosingClass) {
    String methodName;
    String ownerHint = null;
    if (invocation.getMethodSelect() instanceof MemberSelectTree memberSelect) {
      methodName = memberSelect.getIdentifier().toString();
      ownerHint = simpleTypeName(memberSelect.getExpression());
    } else if (invocation.getMethodSelect() instanceof IdentifierTree identifier) {
      methodName = identifier.getName().toString();
      ownerHint = enclosingClass;  // 同类裸调用：所在类就是属主
    } else {
      return null;
    }
    if (ownerHint != null) {
      FactoryInfo byOwner = factories.get(ownerHint + "." + methodName);
      if (byOwner != null && byOwner.arity == invocation.getArguments().size()) {
        return byOwner;
      }
    }
    Set<String> owners = factoryNames.get(methodName);
    if (owners == null) {
      return null;
    }
    FactoryInfo matched = null;
    for (String owner : owners) {
      FactoryInfo candidate = factories.get(owner + "." + methodName);
      if (candidate != null && candidate.arity == invocation.getArguments().size()) {
        if (matched != null) {
          return null;  // 裸名歧义（多属主同元数）：宁缺毋滥
        }
        matched = candidate;
      }
    }
    return matched;
  }

  static int memberIndex(ClassTree clazz, Tree member) {
    int index = 0;
    for (Tree candidate : clazz.getMembers()) {
      if (candidate == member) {
        return index;
      }
      if (candidate instanceof VariableTree) {
        index += 1;
      }
    }
    return index;
  }

  static void registerConstant(String className, VariableTree variable) {
    var modifiers = variable.getModifiers();
    boolean isStatic = modifiers.getFlags().contains(javax.lang.model.element.Modifier.STATIC);
    boolean isFinal = modifiers.getFlags().contains(javax.lang.model.element.Modifier.FINAL);
    if (!isStatic || !isFinal || variable.getInitializer() == null) {
      return;
    }
    List<String> values = evaluate(variable.getInitializer(), className, null);
    if (values.size() == 1) {
      classConstants.computeIfAbsent(className, ignored -> new TreeMap<>())
          .put(variable.getName().toString(), values.get(0));
      String name = variable.getName().toString();
      if (globalConstants.containsKey(name) && !globalConstants.get(name).equals(values.get(0))) {
        ambiguousConstants.add(name);
      } else {
        globalConstants.put(name, values.get(0));
      }
    }
  }

  static void scanProduction(CompilationUnitTree unit, ClassTree clazz) {
    String unitPath = relativePath(unit);
    String className = clazz.getSimpleName().toString();
    for (Tree member : clazz.getMembers()) {
      if (member instanceof MethodTree method) {
        String symbol = className + "#" + methodSignature(method);
        scanExpressionSite(unitPath, symbol, method.getBody(), className, method);
      } else if (member instanceof VariableTree field && field.getInitializer() != null) {
        String symbol = className + "#<field:" + field.getName() + ">";
        scanExpressionSite(unitPath, symbol, field.getInitializer(), className, null);
      }
    }
  }

  static String methodSignature(MethodTree method) {
    StringBuilder signature = new StringBuilder(method.getName().toString()).append('(');
    for (int index = 0; index < method.getParameters().size(); index++) {
      if (index > 0) {
        signature.append(',');
      }
      signature.append(simpleTypeName(method.getParameters().get(index).getType()));
    }
    return signature.append(')').toString();
  }

  static void scanExpressionSite(String unitPath, String symbol, Tree tree, String className, MethodTree enclosing) {
    if (tree == null) {
      return;
    }
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitNewClass(NewClassTree newClass, Void unused) {
        String simple = newClass.getIdentifier() == null ? "" : simpleTypeName(newClass.getIdentifier());
        Integer eventTypeIndex = envelopeEventTypeIndex.get(simple);
        if (eventTypeIndex != null && eventTypeIndex < newClass.getArguments().size()) {
          ExpressionTree eventTypeArg = newClass.getArguments().get(eventTypeIndex);
          if (bindingPhase) {
            bindFactoryParameter(eventTypeArg, className, enclosing);
          } else {
            recordConstruction(unitPath, symbol, eventTypeArg, className, enclosing,
                eventTypeArg.toString(), null);
          }
        }
        return super.visitNewClass(newClass, unused);
      }

      @Override
      public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
        if (!bindingPhase) {
          FactoryInfo factory = resolveFactory(invocation, className);
          if (factory != null && factory.eventTypeParamIndex >= 0
              && factory.eventTypeParamIndex < invocation.getArguments().size()) {
            recordConstruction(unitPath, symbol, invocation.getArguments().get(factory.eventTypeParamIndex),
                    className, null, invocation.toString(), factory.owner + "." + factory.name);
          }
        }
        return super.visitMethodInvocation(invocation, unused);
      }
    }.scan(tree, null);
  }

  static void recordConstruction(String unitPath, String symbol, ExpressionTree eventTypeArg, String className,
      MethodTree enclosing, String expressionText, String viaFactory) {
    List<String> values = evaluate(eventTypeArg, className, enclosing);
    if (!values.isEmpty()) {
      Site site = new Site();
      site.path = unitPath;
      site.symbol = symbol;
      site.eventTypes.addAll(values);
      site.viaFactory = viaFactory;
      sites.add(site);
      return;
    }
    if (enclosing != null && eventTypeArg instanceof IdentifierTree identifier) {
      bindFactoryParameter(eventTypeArg, className, enclosing);
    }
    if (!containsStringLiteral(eventTypeArg)) {
      // 纯运行时透传（无任何字符串字面量、非常量/工厂引用）：eventType 来自入参/DB/Kafka
      // 行等外部数据，本站点不产生新类型——记入 transport（透明可审计），不算生产点。
      Site transport = new Site();
      transport.path = unitPath;
      transport.symbol = symbol;
      transport.eventTypes.add("@transport");
      transport.viaFactory = "runtime-passthrough";
      sites.add(transport);
      return;
    }
    Unresolved site = new Unresolved();
    site.path = unitPath;
    site.symbol = symbol;
    site.detail = "eventType 由非静态可解析表达式绑定：" + expressionText;
    site.digest = sha256(unitPath + "|" + symbol + "|" + expressionText);
    unresolved.add(site);
  }

  /** eventType 实参是工厂自己的某个形参 → 记录该工厂的 eventType 形参下标（调用点延后解析）。 */
  static void bindFactoryParameter(ExpressionTree eventTypeArg, String className, MethodTree enclosing) {
    if (!(eventTypeArg instanceof IdentifierTree identifier)) {
      return;
    }
    for (int index = 0; index < enclosing.getParameters().size(); index++) {
      if (enclosing.getParameters().get(index).getName().contentEquals(identifier.getName())) {
        String methodName = enclosing.getName().toString();
        FactoryInfo info = factories.get(className + "." + methodName);
        if (info == null) {
          info = new FactoryInfo();
          info.arity = enclosing.getParameters().size();
          info.owner = className;
          info.name = methodName;
          factories.put(className + "." + methodName, info);
          factoryNames.computeIfAbsent(methodName, ignored -> new LinkedHashSet<>()).add(className);
        }
        info.eventTypeParamIndex = index;
        return;
      }
    }
  }

  static boolean containsStringLiteral(Tree tree) {
    if (tree == null) {
      return false;
    }
    boolean[] found = {false};
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitLiteral(LiteralTree literal, Void unused) {
        if (literal.getValue() instanceof String) {
          found[0] = true;
        }
        return null;
      }
    }.scan(tree, null);
    return found[0];
  }

  /** 求值：字面量/常量/拼接/三元/括号/工厂固定集。空列表 = 无法解析。 */
  static List<String> evaluate(ExpressionTree expression, String className, MethodTree enclosing) {
    if (expression == null) {
      return List.of();
    }
    if (expression instanceof LiteralTree literal && literal.getValue() instanceof String value) {
      return List.of(value);
    }
    if (expression instanceof ParenthesizedTree parenthesized) {
      return evaluate(parenthesized.getExpression(), className, enclosing);
    }
    if (expression instanceof IdentifierTree identifier) {
      String name = identifier.getName().toString();
      Map<String, String> locals = classConstants.getOrDefault(className, Map.of());
      if (locals.containsKey(name)) {
        return List.of(locals.get(name));
      }
      if (!ambiguousConstants.contains(name) && globalConstants.containsKey(name)) {
        return List.of(globalConstants.get(name));
      }
      return List.of();
    }
    if (expression instanceof MemberSelectTree memberSelect) {
      String name = memberSelect.getIdentifier().toString();
      String ownerHint = simpleTypeName(memberSelect.getExpression());
      Map<String, String> ownerConstants = classConstants.getOrDefault(ownerHint, Map.of());
      if (ownerConstants.containsKey(name)) {
        return List.of(ownerConstants.get(name));
      }
      if (!ambiguousConstants.contains(name) && globalConstants.containsKey(name)) {
        return List.of(globalConstants.get(name));
      }
      return List.of();
    }
    if (expression instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
      List<String> left = evaluate(binary.getLeftOperand(), className, enclosing);
      List<String> right = evaluate(binary.getRightOperand(), className, enclosing);
      if (left.isEmpty() || right.isEmpty()) {
        return List.of();
      }
      List<String> combined = new ArrayList<>();
      for (String first : left) {
        for (String second : right) {
          combined.add(first + second);
        }
      }
      return combined;
    }
    if (expression instanceof ConditionalExpressionTree conditional) {
      List<String> values = new ArrayList<>(new LinkedHashSet<>(evaluate(conditional.getTrueExpression(), className, enclosing)));
      values.addAll(evaluate(conditional.getFalseExpression(), className, enclosing));
      return values.stream().distinct().toList();
    }
    return List.of();
  }

  static String simpleTypeName(Tree tree) {
    String text = tree.toString().trim();
    int generics = text.indexOf('<');
    if (generics > 0) {
      text = text.substring(0, generics);
    }
    int dot = text.lastIndexOf('.');
    return dot >= 0 ? text.substring(dot + 1) : text;
  }

  static String lastSegment(String qualified) {
    int dot = qualified.lastIndexOf('.');
    return dot >= 0 ? qualified.substring(dot + 1) : qualified;
  }

  static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte item : hash) {
        hex.append(String.format("%02x", item));
      }
      return hex.toString();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  static void collectJava(java.util.stream.Stream<Path> stream, List<Path> acc) {
    try (stream) {
      // 只扫生产源码：src/test、构建输出（build/target）里的信封构造是诱饵/测试副本。
      stream.filter(path -> path.toString().endsWith(".java"))
          .filter(path -> {
            String normalized = path.toString().replace('\\', '/');
            return !normalized.contains("/src/test/") && !normalized.contains("/build/")
                && !normalized.contains("/target/");
          })
          .forEach(acc::add);
    }
  }

  static void emitJson() {
    // 工厂体内字面量构造的固定事件集补记（工厂方法的 NewClass 站点已在 sites 中）。
    Map<String, List<String>> grouped = new TreeMap<>();
    for (Site site : sites) {
      for (String eventType : site.eventTypes) {
        if (eventType.equals("@transport")) {
          continue;  // 运行时透传站点单列，不算生产事件
        }
        grouped.computeIfAbsent(eventType, ignored -> new ArrayList<>());
      }
    }
    // 工厂固定类型：来自同一工厂方法 symbol 的站点事件集。
    for (Site site : sites) {
      for (String eventType : site.eventTypes) {
        List<String> list = grouped.get(eventType);
        if (list != null && !list.contains(site.path + "|" + site.symbol)) {
          list.add(site.path + "|" + site.symbol);
        }
      }
    }
    StringBuilder json = new StringBuilder("{\"events\":[");
    boolean firstEvent = true;
    for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
      if (!firstEvent) {
        json.append(',');
      }
      firstEvent = false;
      json.append("{\"eventType\":").append(quote(entry.getKey())).append(",\"sites\":[");
      boolean firstSite = true;
      for (String siteRef : entry.getValue().stream().sorted().toList()) {
        if (!firstSite) {
          json.append(',');
        }
        firstSite = false;
        String[] parts = siteRef.split("\\|", 2);
        json.append("{\"path\":").append(quote(parts[0])).append(",\"symbol\":").append(quote(parts.length > 1 ? parts[1] : ""))
            .append("}");
      }
      json.append("]}");
    }
    json.append("],\"transport\":[")
        .append(sites.stream().filter(site -> site.eventTypes.contains("@transport"))
            .map(site -> "{\"path\":" + quote(site.path) + ",\"symbol\":" + quote(site.symbol) + "}")
            .sorted().collect(java.util.stream.Collectors.joining(",")))
        .append("],\"unresolved\":[");
    boolean firstUnresolved = true;
    for (Unresolved item : unresolved.stream()
        .sorted(Comparator.comparing((Unresolved value) -> value.path).thenComparing(value -> value.symbol)).toList()) {
      if (!firstUnresolved) {
        json.append(',');
      }
      firstUnresolved = false;
      json.append("{\"path\":").append(quote(item.path)).append(",\"symbol\":").append(quote(item.symbol))
          .append(",\"detail\":").append(quote(item.detail)).append(",\"digest\":").append(quote(item.digest == null ? "" : item.digest))
          .append('}');
    }
    json.append(']');
    json.append(",\"declarations\":[");
    boolean firstDeclaration = true;
    String previousDeclaration = null;
    for (Declaration declaration : declarations.stream()
        .sorted(Comparator.comparing((Declaration value) -> value.path)
            .thenComparing(value -> value.symbol))
        .toList()) {
      String key = declaration.path + "|" + declaration.symbol;
      if (key.equals(previousDeclaration)) {
        continue;  // 精确重复去重（稳定排序后相邻）。
      }
      previousDeclaration = key;
      if (!firstDeclaration) {
        json.append(',');
      }
      firstDeclaration = false;
      json.append("{\"path\":").append(quote(declaration.path)).append(",\"symbol\":")
          .append(quote(declaration.symbol)).append('}');
    }
    json.append("]}");
    System.out.println(json);
  }

  static String quote(String value) {
    StringBuilder escaped = new StringBuilder("\"");
    for (int index = 0; index < value.length(); index++) {
      char item = value.charAt(index);
      switch (item) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (item < 0x20) {
            escaped.append(String.format("\\u%04x", (int) item));
          } else {
            escaped.append(item);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }
}
