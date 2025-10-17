package edu.university.ecs.lab.common.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.error.Error;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.EndpointTemplate;
import edu.university.ecs.lab.common.models.enums.HttpMethod;
import edu.university.ecs.lab.common.models.enums.RestCallTemplate;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.services.LoggerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Static utility class for parsing a file and returning associated models from code structure.
 */
public class SourceToObjectUtils {
    private static CompilationUnit cu;
    private static String microserviceName = "";
    private static String path;
    private static String className;
    private static String packageName;
    private static String packageAndClassName;
    private static CombinedTypeSolver combinedTypeSolver;
    private static Config config;
    private static final Map<String, Set<String>> ANNOTATION_META_NAMES = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> ANNOTATION_META_EXPRESSIONS = new ConcurrentHashMap<>();


    private static void generateStaticValues(File sourceFile, Config config1) {
        // Parse the highest level node being compilation unit
        config = config1;
        try {
            cu = StaticJavaParser.parse(sourceFile);
        } catch (Exception e) {
            LoggerManager.warn(() -> "Failed to parse  " + sourceFile.getPath());
            microserviceName = "";
            return;
//            Error.reportAndExit(Error.JPARSE_FAILED, Optional.of(e));
        }
        if (!cu.findAll(PackageDeclaration.class).isEmpty()) {
            packageName = cu.findAll(PackageDeclaration.class).get(0).getNameAsString();
            packageAndClassName = packageName + "." + sourceFile.getName().replace(".java", "");
        }
        indexAnnotationDeclarations();
        path = FileUtils.localPathToGitPath(sourceFile.getPath(), config.getRepoName());

        TypeSolver reflectionTypeSolver = new ReflectionTypeSolver();
        TypeSolver javaParserTypeSolver = new JavaParserTypeSolver(FileUtils.getRepositoryPath(config.getRepoName()));

        combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(reflectionTypeSolver);
        combinedTypeSolver.add(javaParserTypeSolver);

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);
        className = sourceFile.getName().replace(".java", "");

    }

    /**
     * This method parses a Java class file and return a JClass object.
     *
     * @param sourceFile the file to parse
     * @return the JClass object representing the file
     */
    public static JClass parseClass(File sourceFile, Config config, String microserviceName) {
        // Guard condition
        if(Objects.isNull(sourceFile) || FileUtils.isConfigurationFile(sourceFile.getPath())) {
            LoggerManager.warn(() -> "JClass filtered  " + sourceFile.getPath() + " is config or null");
            return null;
        }

        if (!FileUtils.isCodeFile(sourceFile.getPath())) {
            LoggerManager.debug(() -> "Skipping unsupported source file " + sourceFile.getPath());
            return null;
        }

        String lowerName = sourceFile.getName().toLowerCase(Locale.ROOT);

        if (lowerName.endsWith(".groovy")) {
            if (!microserviceName.isEmpty()) {
                SourceToObjectUtils.microserviceName = microserviceName;
            }
            return GroovySourceParser.parse(sourceFile, config, microserviceName);
        }

        if (lowerName.endsWith(".kt") || lowerName.endsWith(".kts")) {
            if (!microserviceName.isEmpty()) {
                SourceToObjectUtils.microserviceName = microserviceName;
            }
            return KotlinSourceParser.parse(sourceFile, config, microserviceName);
        }

        if (!lowerName.endsWith(".java")) {
            LoggerManager.debug(() -> "Skipping non-Java source file " + sourceFile.getPath());
            return null;
        }

        generateStaticValues(sourceFile, config);
        if (!microserviceName.isEmpty()) {
            SourceToObjectUtils.microserviceName = microserviceName;
        }

        // Calculate early to determine classrole based on annotation, filter for class based annotations only
        Set<AnnotationExpr> classAnnotations = filterClassAnnotations();
        AnnotationExpr requestMapping = findRequestMappingAnnotation(classAnnotations);

        ClassRole classRole = parseClassRole(classAnnotations);

        // Return unknown classRoles where annotation not found
        if (classRole.equals(ClassRole.UNKNOWN)) {
            LoggerManager.warn(() -> "JClass filtered  " + sourceFile.getPath() + " class role unknown");
            return null;
        }

        JClass jClass = null;
        if(classRole == ClassRole.FEIGN_CLIENT) {
            jClass = handleFeignClient(requestMapping, classAnnotations);
        } else if(classRole == ClassRole.REP_REST_RSC) {
            jClass = handleRepositoryRestResource(requestMapping, classAnnotations);
        } else {
            jClass = new JClass(
                    className,
                    path,
                    packageName,
                    classRole,
                    parseMethods(cu.findAll(MethodDeclaration.class), requestMapping),
                    parseFields(cu.findAll(FieldDeclaration.class)),
                    parseAnnotations(classAnnotations),
                    parseMethodCalls(cu.findAll(MethodDeclaration.class)),
                    cu.findAll(ClassOrInterfaceDeclaration.class).get(0).getImplementedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()));
        }

        // Build the JClass
        return jClass;

    }


    /**
     * This method parses methodDeclarations list and returns a Set of Method models
     *
     * @param methodDeclarations the list of methodDeclarations to be parsed
     * @return a set of Method models representing the MethodDeclarations
     */
    public static Set<Method> parseMethods(List<MethodDeclaration> methodDeclarations, AnnotationExpr requestMapping) {
        // Get params and returnType
        Set<Method> methods = new HashSet<>();

        for (MethodDeclaration methodDeclaration : methodDeclarations) {
            Set<edu.university.ecs.lab.common.models.ir.Parameter> parameters = new HashSet<>();
            for (Parameter parameter : methodDeclaration.getParameters()) {
                parameters.add(new edu.university.ecs.lab.common.models.ir.Parameter(parameter, packageAndClassName));
            }

            Method method = new Method(
                    methodDeclaration.getNameAsString(),
                    packageAndClassName,
                    parameters,
                    methodDeclaration.getTypeAsString(),
                    parseAnnotations(methodDeclaration.getAnnotations()),
                    microserviceName,
                    className);

            Collection<Endpoint> endpoints = convertValidEndpoints(methodDeclaration, method, requestMapping);
            if (endpoints.isEmpty()) {
                methods.add(method);
            } else {
                methods.addAll(endpoints);
            }
        }

        return methods;
    }

    /**
     * This method converts a valid Method to an Endpoint
     *
     * @param methodDeclaration the MethodDeclaration associated with Method
     * @param method            the Method to be converted
     * @param requestMapping    the class level requestMapping
     * @return returns method if it is invalid, otherwise a new Endpoint
     */
    public static Collection<Endpoint> convertValidEndpoints(MethodDeclaration methodDeclaration, Method method, AnnotationExpr requestMapping) {
        List<Endpoint> endpoints = new ArrayList<>();
        for (AnnotationExpr ae : methodDeclaration.getAnnotations()) {
            Optional<String> resolvedMappingName = resolveEndpointMappingName(ae);
            if (resolvedMappingName.isPresent()) {
                AnnotationExpr effectiveAnnotation = resolveEffectiveMappingAnnotation(ae, resolvedMappingName.get());
                List<EndpointTemplate> templates = EndpointTemplate.from(requestMapping, effectiveAnnotation, resolvedMappingName.get());
                for (EndpointTemplate template : templates) {
                    endpoints.add(new Endpoint(method, template.getUrl(), template.getHttpMethod()));
                }
            }
        }

        return endpoints;
    }



    /**
     * This method parses methodDeclarations list and returns a Set of MethodCall models
     *
     * @param methodDeclarations the list of methodDeclarations to be parsed
     * @return a set of MethodCall models representing MethodCallExpressions found in the MethodDeclarations
     */
    public static List<MethodCall> parseMethodCalls(List<MethodDeclaration> methodDeclarations) {
        List<MethodCall> methodCalls = new ArrayList<>();

        // loop through method calls
        for (MethodDeclaration methodDeclaration : methodDeclarations) {
            for (MethodCallExpr mce : methodDeclaration.findAll(MethodCallExpr.class)) {
                String methodName = mce.getNameAsString();

                String calledServiceName = getCallingObjectName(mce);
                String calledServiceType = getCallingObjectType(mce);

                String parameterContents = mce.getArguments().stream().map(Objects::toString).collect(Collectors.joining(","));

                if (Objects.nonNull(calledServiceName)) {
                    MethodCall methodCall = new MethodCall(methodName, packageAndClassName, calledServiceType, calledServiceName,
                            methodDeclaration.getNameAsString(), parameterContents, microserviceName, className);

                    methodCall = convertValidRestCalls(mce, methodCall);

                    methodCalls.add(methodCall);
                }
            }
        }

        return methodCalls;
    }

    /**
     * This method converts a valid MethodCall to an RestCall
     *
     * @param methodCallExpr the MethodDeclaration associated with Method
     * @param methodCall     the MethodCall to be converted
     * @return returns methodCall if it is invalid, otherwise a new RestCall
     */
    public static MethodCall convertValidRestCalls(MethodCallExpr methodCallExpr, MethodCall methodCall) {
        if ((!RestCallTemplate.REST_OBJECTS.contains(methodCall.getObjectType()) || !RestCallTemplate.REST_METHODS.contains(methodCallExpr.getNameAsString()))) {
            return methodCall;
        }

        RestCallTemplate restCallTemplate = new RestCallTemplate(methodCallExpr,methodCall, cu);

        if (restCallTemplate.getUrl().isEmpty()) {
            return methodCall;
        }

        return new RestCall(methodCall, restCallTemplate.getUrl(), restCallTemplate.getHttpMethod());
    }

    /**
     * This method converts a list of FieldDeclarations to a set of Field models
     *
     * @param fieldDeclarations the field declarations to parse
     * @return the set of Field models
     */
    private static Set<Field> parseFields(List<FieldDeclaration> fieldDeclarations) {
        Set<Field> javaFields = new HashSet<>();

        // loop through class declarations
        for (FieldDeclaration fd : fieldDeclarations) {
            for (VariableDeclarator variable : fd.getVariables()) {
                javaFields.add(new Field(variable.getNameAsString(), packageAndClassName, variable.getTypeAsString()));
            }

        }

        return javaFields;
    }


    /**
     * Get the name of the object a method is being called from (callingObj.methodName())
     *
     * @return the name of the object the method is being called from
     */
    private static String getCallingObjectName(MethodCallExpr mce) {


        Expression scope = mce.getScope().orElse(null);

        if (Objects.nonNull(scope) && scope instanceof NameExpr) {
            NameExpr fae = scope.asNameExpr();
            return fae.getNameAsString();
        }

        return "";

    }

    private static String getCallingObjectType(MethodCallExpr mce) {

        Expression scope = mce.getScope().orElse(null);

        if (Objects.isNull(scope)) {
            return "";
        }

        try {
            // Resolve the type of the object
            var resolvedType = JavaParserFacade.get(combinedTypeSolver).getType(scope);
            List<String> parts = List.of(((ReferenceTypeImpl) resolvedType).getQualifiedName().split("\\."));
            if(parts.isEmpty()) {
                return "";
            }

            return parts.get(parts.size() - 1);
        } catch (Exception e) {
            if(e instanceof UnsolvedSymbolException && ((UnsolvedSymbolException) e).getName() != null) {
                return ((UnsolvedSymbolException) e).getName();
            }
            return "";
        }
    }


    /**
     * This method parses a list of annotation expressions and returns a set of Annotation models
     *
     * @param annotationExprs the annotation expressions to parse
     * @return the Set of Annotation models
     */
    private static Set<Annotation> parseAnnotations(Iterable<AnnotationExpr> annotationExprs) {
        Set<Annotation> annotations = new HashSet<>();

        for (AnnotationExpr ae : annotationExprs) {
            annotations.add(new Annotation(ae, packageAndClassName));
        }

        return annotations;
    }

    /**
     * This method searches a list of Annotation expressions and returns a ClassRole found
     *
     * @param annotations the list of annotations to search
     * @return the ClassRole determined
     */
    private static ClassRole parseClassRole(Set<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            ClassRole classRole = resolveClassRole(annotation, new HashSet<>());
            if (!ClassRole.UNKNOWN.equals(classRole)) {
                return classRole;
            }
        }
        return ClassRole.UNKNOWN;
    }

    private static ClassRole resolveClassRole(AnnotationExpr annotation, Set<String> visited) {
        ClassRole directRole = mapAnnotationToRole(annotation.getNameAsString());
        if (!ClassRole.UNKNOWN.equals(directRole)) {
            return directRole;
        }

        try {
            ResolvedAnnotationDeclaration resolvedAnnotation = annotation.resolve();
            String qualifiedName = resolvedAnnotation.getQualifiedName();
            if (qualifiedName != null) {
                ClassRole qualifiedRole = mapAnnotationToRole(qualifiedName);
                if (!ClassRole.UNKNOWN.equals(qualifiedRole)) {
                    return qualifiedRole;
                }
            }

            String visitKey = qualifiedName != null ? qualifiedName : annotation.getNameAsString();
            if (!visitKey.isEmpty() && !visited.add(visitKey)) {
                return ClassRole.UNKNOWN;
            }

            Optional<? extends AnnotationDeclaration> declaration = resolvedAnnotation.toAst();
            if (declaration.isPresent()) {
                for (AnnotationExpr metaAnnotation : declaration.get().getAnnotations()) {
                    ClassRole metaRole = resolveClassRole(metaAnnotation, visited);
                    if (!ClassRole.UNKNOWN.equals(metaRole)) {
                        return metaRole;
                    }
                }
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            LoggerManager.debug(() -> String.format("Unable to resolve class role for annotation %s: %s", annotation, e.getMessage()));
        } catch (RuntimeException e) {
            LoggerManager.debug(() -> String.format("Unexpected error while resolving class role for annotation %s: %s", annotation, e.getMessage()));
        }

        return resolveClassRoleFromCache(annotation.getNameAsString(), visited);
    }

    static ClassRole mapAnnotationToRole(String annotationName) {
        String simpleName = simpleName(annotationName);
        switch (simpleName) {
            case "RestController":
            case "Controller":
                return ClassRole.CONTROLLER;
            case "Service":
                return ClassRole.SERVICE;
            case "Repository":
                return ClassRole.REPOSITORY;
            case "RepositoryRestResource":
                return ClassRole.REP_REST_RSC;
            case "Entity":
            case "Embeddable":
                return ClassRole.ENTITY;
            case "FeignClient":
                return ClassRole.FEIGN_CLIENT;
            default:
                return ClassRole.UNKNOWN;
        }
    }

    private static ClassRole resolveClassRoleFromCache(String annotationName, Set<String> visited) {
        for (String variant : annotationNameVariants(annotationName)) {
            if (!visited.add(variant)) {
                continue;
            }

            Set<String> metaNames = ANNOTATION_META_NAMES.getOrDefault(variant, Collections.emptySet());
            for (String metaName : metaNames) {
                ClassRole direct = mapAnnotationToRole(metaName);
                if (!ClassRole.UNKNOWN.equals(direct)) {
                    return direct;
                }

                ClassRole nested = resolveClassRoleFromCache(metaName, visited);
                if (!ClassRole.UNKNOWN.equals(nested)) {
                    return nested;
                }
            }
        }

        return ClassRole.UNKNOWN;
    }

    private static Optional<String> resolveEndpointMappingName(AnnotationExpr annotationExpr) {
        return resolveEndpointMappingName(annotationExpr, new HashSet<>());
    }

    private static Optional<String> resolveEndpointMappingName(AnnotationExpr annotationExpr, Set<String> visited) {
        String simpleName = simpleName(annotationExpr.getNameAsString());
        if (EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(simpleName)) {
            return Optional.of(simpleName);
        }

        try {
            ResolvedAnnotationDeclaration resolvedAnnotation = annotationExpr.resolve();
            String qualifiedName = resolvedAnnotation.getQualifiedName();
            if (qualifiedName != null) {
                String qualifiedSimpleName = simpleName(qualifiedName);
                if (EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(qualifiedSimpleName)) {
                    return Optional.of(qualifiedSimpleName);
                }
            }

            String visitKey = qualifiedName != null ? qualifiedName : annotationExpr.getNameAsString();
            if (!visitKey.isEmpty() && !visited.add(visitKey)) {
                return Optional.empty();
            }

            Optional<? extends AnnotationDeclaration> declaration = resolvedAnnotation.toAst();
            if (declaration.isPresent()) {
                for (AnnotationExpr metaAnnotation : declaration.get().getAnnotations()) {
                    Optional<String> resolvedMapping = resolveEndpointMappingName(metaAnnotation, visited);
                    if (resolvedMapping.isPresent()) {
                        return resolvedMapping;
                    }
                }
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            LoggerManager.debug(() -> String.format("Unable to resolve endpoint annotation %s: %s", annotationExpr, e.getMessage()));
        } catch (RuntimeException e) {
            LoggerManager.debug(() -> String.format("Unexpected error while resolving endpoint annotation %s: %s", annotationExpr, e.getMessage()));
        }

        return resolveEndpointMappingNameFromCache(annotationExpr.getNameAsString(), visited);
    }

    private static Optional<String> resolveEndpointMappingNameFromCache(String annotationName, Set<String> visited) {
        for (String variant : annotationNameVariants(annotationName)) {
            if (!visited.add(variant)) {
                continue;
            }

            Set<String> metaNames = ANNOTATION_META_NAMES.getOrDefault(variant, Collections.emptySet());
            for (String metaName : metaNames) {
                String simple = simpleName(metaName);
                if (EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(simple)) {
                    return Optional.of(simple);
                }

                Optional<String> nested = resolveEndpointMappingNameFromCache(metaName, visited);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }

        return Optional.empty();
    }

    private static String simpleName(String name) {
        if (name == null) {
            return "";
        }
        int lastDotIndex = name.lastIndexOf('.');
        return lastDotIndex >= 0 ? name.substring(lastDotIndex + 1) : name;
    }

    private static Set<String> annotationNameVariants(String name) {
        if (name == null || name.isEmpty()) {
            return Collections.emptySet();
        }

        String simple = simpleName(name);
        if (simple.equals(name)) {
            return Set.of(name);
        }

        return Set.of(name, simple);
    }

    private static void indexAnnotationDeclarations() {
        if (cu == null) {
            return;
        }

        Optional<String> optionalPackage = cu.getPackageDeclaration().map(PackageDeclaration::getNameAsString);

        for (AnnotationDeclaration declaration : cu.findAll(AnnotationDeclaration.class)) {
            String simple = declaration.getNameAsString();
            String qualified = optionalPackage.map(pkg -> pkg + "." + simple).orElse(simple);

            for (AnnotationExpr metaAnnotation : declaration.getAnnotations()) {
                registerAnnotationMetadata(simple, metaAnnotation);
                registerAnnotationMetadata(qualified, metaAnnotation);
            }
        }
    }

    private static void registerAnnotationMetadata(String annotationName, AnnotationExpr metaAnnotation) {
        if (annotationName == null || annotationName.isEmpty() || metaAnnotation == null) {
            return;
        }

        ANNOTATION_META_NAMES
                .computeIfAbsent(annotationName, key -> new LinkedHashSet<>())
                .add(metaAnnotation.getNameAsString());

        ANNOTATION_META_EXPRESSIONS
                .computeIfAbsent(annotationName, key -> new LinkedHashSet<>())
                .add(metaAnnotation.toString());
    }

    private static AnnotationExpr findRequestMappingAnnotation(Set<AnnotationExpr> classAnnotations) {
        for (AnnotationExpr annotation : classAnnotations) {
            Optional<String> mapping = resolveEndpointMappingName(annotation);
            if (mapping.isPresent() && "RequestMapping".equals(mapping.get())) {
                return resolveEffectiveMappingAnnotation(annotation, mapping.get());
            }
        }

        for (AnnotationExpr annotation : classAnnotations) {
            Optional<AnnotationExpr> meta = resolveMetaAnnotationExpression(annotation.getNameAsString(), "RequestMapping", new HashSet<>());
            if (meta.isPresent()) {
                return meta.get();
            }
        }

        return null;
    }

    private static Optional<AnnotationExpr> resolveMetaAnnotationExpression(String annotationName, String targetSimpleName, Set<String> visited) {
        for (String variant : annotationNameVariants(annotationName)) {
            if (!visited.add(variant)) {
                continue;
            }

            Set<String> expressions = ANNOTATION_META_EXPRESSIONS.getOrDefault(variant, Collections.emptySet());
            for (String expression : expressions) {
                AnnotationExpr parsed;
                try {
                    parsed = StaticJavaParser.parseAnnotation(expression);
                } catch (Exception e) {
                    LoggerManager.debug(() -> String.format("Unable to parse meta-annotation expression %s: %s", expression, e.getMessage()));
                    continue;
                }

                String parsedName = parsed.getNameAsString();
                if (targetSimpleName.equals(simpleName(parsedName))) {
                    return Optional.of(parsed);
                }

                Optional<AnnotationExpr> nested = resolveMetaAnnotationExpression(parsedName, targetSimpleName, visited);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }

        return Optional.empty();
    }

    private static AnnotationExpr resolveEffectiveMappingAnnotation(AnnotationExpr annotationExpr, String mappingName) {
        if (annotationExpr == null || mappingName == null) {
            return annotationExpr;
        }

        if (hasExplicitPath(annotationExpr)) {
            return annotationExpr;
        }

        Optional<AnnotationExpr> meta = resolveMetaAnnotationExpression(annotationExpr.getNameAsString(), mappingName, new HashSet<>());
        return meta.orElse(annotationExpr);
    }

    private static boolean hasExplicitPath(AnnotationExpr annotationExpr) {
        if (annotationExpr == null) {
            return false;
        }

        if (annotationExpr.isSingleMemberAnnotationExpr()) {
            return true;
        }

        if (annotationExpr.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotationExpr.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get the name of the microservice based on the file
     *
     * @param sourceFile the file we are getting microservice name for
     * @return
     */
    private static String getMicroserviceName(File sourceFile) {
        List<String> split = Arrays.asList(sourceFile.getPath().split(FileUtils.SPECIAL_SEPARATOR));
        return split.get(3);
    }

    /**
     * FeignClient represents an interface for making rest calls to a service
     * other than the current one. As such this method converts feignClient
     * interfaces into a service class whose methods simply contain the exact
     * rest call outlined by the interface annotations.
     *
     * @param classAnnotations
     * @return
     */
    private static JClass handleFeignClient(AnnotationExpr requestMapping, Set<AnnotationExpr> classAnnotations) {

        // Parse the methods
        Set<Method> methods = parseMethods(cu.findAll(MethodDeclaration.class), requestMapping);

        // New methods for conversion
        Set<Method> newMethods = new HashSet<>();
        // New rest calls for conversion
        List<MethodCall> newRestCalls = new ArrayList<>();

        // For each method that is detected as an endpoint convert into a Method + RestCall
        for(Method method : methods) {
            if(method instanceof Endpoint) {
                Endpoint endpoint = (Endpoint) method;
                newMethods.add(new Method(method.getName(), packageAndClassName, method.getParameters(), method.getReturnType(), method.getAnnotations(), method.getMicroserviceName(), method.getClassName()));

                StringBuilder queryParams = new StringBuilder();
                for(edu.university.ecs.lab.common.models.ir.Parameter parameter : method.getParameters()) {
                    for(Annotation annotation : parameter.getAnnotations()) {
                        if(annotation.getName().equals("RequestParam")) {
                            queryParams.append("&");
                            if(annotation.getAttributes().containsKey("default")) {
                                queryParams.append(annotation.getAttributes().get("default"));
                            } else if(annotation.getAttributes().containsKey("name")) {
                                queryParams.append(annotation.getAttributes().get("name"));
                            } else {
                                queryParams.append(parameter.getName());
                            }

                            queryParams.append("={?}");
                        }
                    }
                }

                if(!queryParams.isEmpty()) {
                    queryParams.replace(0, 1, "?");
                }

                newRestCalls.add(new RestCall(new MethodCall("exchange", packageAndClassName, "RestCallTemplate", "restCallTemplate", method.getName(), "", endpoint.getMicroserviceName(), endpoint.getClassName()), endpoint.getUrl() + queryParams, endpoint.getHttpMethod()));
            } else {
                newMethods.add(method);
            }
        }


        // Build the JClass
        return new JClass(
                className,
                path,
                packageName,
                ClassRole.FEIGN_CLIENT,
                newMethods,
                parseFields(cu.findAll(FieldDeclaration.class)),
                parseAnnotations(classAnnotations),
                newRestCalls,
                cu.findAll(ClassOrInterfaceDeclaration.class).get(0).getImplementedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()));
    }

    public static ConfigFile parseConfigurationFile(File file, Config config) {
        if(file.getName().endsWith(".yml")) {
            return NonJsonReadWriteUtils.readFromYaml(file.getPath(), config);
        } else if(file.getName().equals("DockerFile")) {
            return NonJsonReadWriteUtils.readFromDocker(file.getPath(), config);
        } else if(file.getName().equals("pom.xml")) {
            return NonJsonReadWriteUtils.readFromPom(file.getPath(), config);
        } else if (file.getName().equals("build.gradle")){
            return NonJsonReadWriteUtils.readFromGradle(file.getPath(), config);
        } else if (file.getName().equals("build.gradle.kts")) {
            return NonJsonReadWriteUtils.readFromGradle(file.getPath(), config);
        } else {
            return null;
        }
    }

    private static Set<AnnotationExpr> filterClassAnnotations() {
        Set<AnnotationExpr> classAnnotations = new HashSet<>();
        for (AnnotationExpr ae : cu.findAll(AnnotationExpr.class)) {
            if (ae.getParentNode().isPresent()) {
                Node n = ae.getParentNode().get();
                if (n instanceof ClassOrInterfaceDeclaration) {
                    classAnnotations.add(ae);
                }
            }
        }
        return classAnnotations;
    }

    /**
     * FeignClient represents an interface for making rest calls to a service
     * other than the current one. As such this method converts feignClient
     * interfaces into a service class whose methods simply contain the exact
     * rest call outlined by the interface annotations.
     *
     * @param classAnnotations
     * @return
     */
    private static JClass handleRepositoryRestResource(AnnotationExpr requestMapping, Set<AnnotationExpr> classAnnotations) {

        // Parse the methods
        Set<Method> methods = parseMethods(cu.findAll(MethodDeclaration.class), requestMapping);

        // New methods for conversion
        Set<Method> newEndpoints = new HashSet<>();
        // New rest calls for conversion
        List<MethodCall> newRestCalls = new ArrayList<>();

        // Arbitrary preURL naming scheme if not defined in the annotation
        String preURL = "/" + className.toLowerCase().replace("repository", "") + "s";

        for(AnnotationExpr annotation : classAnnotations) {
            if(annotation.getNameAsString().equals("RepositoryRestResource")) {
                if (requestMapping instanceof NormalAnnotationExpr) {
                    NormalAnnotationExpr nae = (NormalAnnotationExpr) requestMapping;
                    for (MemberValuePair pair : nae.getPairs()) {
                        if (pair.getNameAsString().equals("path")) {
                            preURL += pair.getValue().toString();
                            break;
                        }
                    }
                } else if (requestMapping instanceof SingleMemberAnnotationExpr) {
                    preURL += annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
                    break;
                }
            }
        }

        // For each method that is detected as an endpoint convert into a Method + RestCall
        for(Method method : methods) {

            String url = "/search";
            boolean restResourceFound = false;
            boolean isExported = true;
            for(Annotation ae : method.getAnnotations()) {
                if (requestMapping instanceof NormalAnnotationExpr) {
                    NormalAnnotationExpr nae = (NormalAnnotationExpr) requestMapping;
                    for (MemberValuePair pair : nae.getPairs()) {
                        if (pair.getNameAsString().equals("path")) {
                            preURL = pair.getValue().toString();
                            restResourceFound = true;
                        } else if(pair.getNameAsString().equals("exported")) {
                            if(pair.getValue().toString().equals("false")) {
                                isExported = false;
                            }
                        }
                    }
                }
            }

            // This method not exported (exposed) as an Endpoint
            if(!isExported) {
                continue;
            }

            // If no restResource annotation found we use default /search url start
            if(!restResourceFound) {
                url += ("/" + method.getName());
            }

            Endpoint endpoint = new Endpoint(method, preURL + url, HttpMethod.GET);
            newEndpoints.add(endpoint);
        }


        // Build the JClass
        return new JClass(
                className,
                path,
                packageName,
                ClassRole.REP_REST_RSC,
                newEndpoints,
                parseFields(cu.findAll(FieldDeclaration.class)),
                parseAnnotations(classAnnotations),
                newRestCalls,
                cu.findAll(ClassOrInterfaceDeclaration.class).get(0).getImplementedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()));
    }

    private static JClass handleJS(String filePath) {
        JClass jClass = new JClass(filePath, filePath, "", ClassRole.FEIGN_CLIENT, new HashSet<>(), new HashSet<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>());
        try {
            Set<RestCall> restCalls = new HashSet<>();
            // Command to run Node.js script
            ProcessBuilder processBuilder = new ProcessBuilder("node", "scripts/parser.js");
            Process process = processBuilder.start();

            // Capture the output
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] split = line.split(";");
                restCalls.add(new RestCall(split[0], split[1], split[2], split[3], split[4], split[5], split[6], split[7]));
                System.out.println("Node.js Output: " + line);
            }

            // Wait for the Node.js process to complete
            int exitCode = process.waitFor();
            System.out.println("Node.js process exited with code: " + exitCode);
        } catch (Exception e) {
            System.err.println(e);
            System.exit(1);
        }

        return jClass;
    }
}
