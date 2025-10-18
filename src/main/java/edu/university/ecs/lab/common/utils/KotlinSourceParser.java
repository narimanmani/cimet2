package edu.university.ecs.lab.common.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.EndpointTemplate;
import edu.university.ecs.lab.common.models.ir.Annotation;
import edu.university.ecs.lab.common.models.ir.Endpoint;
import edu.university.ecs.lab.common.models.ir.Field;
import edu.university.ecs.lab.common.models.ir.JClass;
import edu.university.ecs.lab.common.models.ir.Method;
import edu.university.ecs.lab.common.models.ir.MethodCall;
import edu.university.ecs.lab.common.models.ir.Parameter;
import edu.university.ecs.lab.common.services.LoggerManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lightweight parser that extracts Spring endpoints from Kotlin sources.
 */
final class KotlinSourceParser {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^package\\s+([\\w\\.]+)");
    private static final Pattern CLASS_PATTERN = Pattern.compile("^(?:public|protected|private|internal)?\\s*(?:data\\s+|sealed\\s+|open\\s+|abstract\\s+|final\\s+)?(class|object)\\s+([A-Za-z0-9_]+)");
    private static final Pattern FUN_PATTERN = Pattern.compile("\\bfun\\s+(?:<[^>]+>\\s*)?(?:[A-Za-z0-9_<>?:\\s]*?\\s+)?([A-Za-z0-9_]+)\\s*\\(");

    private KotlinSourceParser() {
    }

    static JClass parse(File sourceFile, Config config, String microserviceName) {
        List<String> lines;
        try {
            lines = Files.readAllLines(sourceFile.toPath());
        } catch (IOException e) {
            LoggerManager.warn(() -> "Failed to read Kotlin source " + sourceFile.getPath() + ": " + e.getMessage());
            return null;
        }

        if (lines.isEmpty()) {
            return null;
        }

        String packageName = extractPackage(lines);
        String className = sourceFile.getName().replaceAll("\\.kts?$", "");
        String packageAndClassName = packageName.isEmpty() ? className : packageName + "." + className;
        String gitPath = FileUtils.localPathToGitPath(sourceFile.getPath(), config.getRepoName());

        List<KotlinAnnotation> pendingAnnotations = new ArrayList<>();
        Set<Annotation> classAnnotations = new LinkedHashSet<>();
        Optional<AnnotationExpr> classRequestMapping = Optional.empty();
        ClassRole classRole = ClassRole.UNKNOWN;
        boolean insideClass = false;
        int braceDepth = 0;
        boolean inBlockComment = false;
        boolean discoveredEndpoint = false;

        Set<Method> methods = new LinkedHashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String originalLine = lines.get(i);
            String trimmed = originalLine.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }

            if (trimmed.startsWith("/*")) {
                inBlockComment = !trimmed.contains("*/");
                continue;
            }

            if (trimmed.startsWith("//")) {
                continue;
            }

            if (trimmed.startsWith("package ")) {
                continue;
            }

            if (trimmed.startsWith("@")) {
                AnnotationParseResult result = parseAnnotation(lines, i);
                result.annotation().ifPresent(pendingAnnotations::add);
                i = result.nextIndex();
                continue;
            }

            if (!insideClass) {
                Matcher matcher = CLASS_PATTERN.matcher(trimmed);
                if (matcher.find()) {
                    insideClass = true;
                    className = matcher.group(2);
                    packageAndClassName = packageName.isEmpty() ? className : packageName + "." + className;

                    final String fqcnForClassAnnotations = packageAndClassName;
                    classAnnotations = pendingAnnotations.stream()
                            .map(annotation -> annotation.toModel(fqcnForClassAnnotations))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    classRole = determineClassRole(pendingAnnotations);
                    classRequestMapping = findMappingAnnotation(pendingAnnotations)
                            .flatMap(KotlinAnnotation::toAnnotationExpr);
                    pendingAnnotations.clear();

                    braceDepth += countChar(trimmed, '{') - countChar(trimmed, '}');
                    continue;
                }
            }

            if (!insideClass) {
                continue;
            }

            braceDepth += countChar(trimmed, '{') - countChar(trimmed, '}');
            if (braceDepth <= 0) {
                insideClass = false;
                continue;
            }

            if (!trimmed.contains("fun ")) {
                continue;
            }

            Signature signature = parseFunctionSignature(lines, i);
            if (signature == null) {
                continue;
            }

            String methodName = signature.name();
            if (methodName == null || methodName.isEmpty()) {
                continue;
            }

            List<KotlinAnnotation> methodAnnotations = new ArrayList<>(pendingAnnotations);
            pendingAnnotations.clear();

            final String fqcnForMethodAnnotations = packageAndClassName;
            Set<Annotation> methodAnnotationModels = methodAnnotations.stream()
                    .map(annotation -> annotation.toModel(fqcnForMethodAnnotations))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<Parameter> parameters = parseParameters(packageAndClassName, signature.parameters());
            String returnType = signature.returnType();
            if (returnType == null || returnType.isEmpty()) {
                returnType = "Unit";
            }

            Method method = new Method(methodName, packageAndClassName, parameters, returnType, methodAnnotationModels,
                    microserviceName, className);

            Optional<KotlinAnnotation> mappingAnnotation = findMappingAnnotation(methodAnnotations);
            List<EndpointTemplate> templates = new ArrayList<>();
            if (mappingAnnotation.isPresent()) {
                Optional<AnnotationExpr> mappingExpr = mappingAnnotation.get().toAnnotationExpr();
                if (mappingExpr.isPresent()) {
                    templates = EndpointTemplate.from(classRequestMapping.orElse(null), mappingExpr.get(),
                            mappingAnnotation.get().simpleName());
                }
            }

            if (!templates.isEmpty()) {
                discoveredEndpoint = true;
                for (EndpointTemplate template : templates) {
                    methods.add(new Endpoint(method, template.getUrl(), template.getHttpMethod()));
                }
            } else {
                methods.add(method);
            }
        }

        if (!discoveredEndpoint) {
            return null;
        }

        if (ClassRole.UNKNOWN.equals(classRole)) {
            classRole = ClassRole.CONTROLLER;
        }

        return new JClass(
                className,
                gitPath,
                packageName,
                classRole,
                methods,
                new HashSet<Field>(),
                classAnnotations,
                new ArrayList<MethodCall>(),
                new HashSet<>()
        );
    }

    private static String extractPackage(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = PACKAGE_PATTERN.matcher(line.trim());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static int countChar(String text, char symbol) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == symbol) {
                count++;
            }
        }
        return count;
    }

    private static Signature parseFunctionSignature(List<String> lines, int startIndex) {
        StringBuilder builder = new StringBuilder();
        int parenBalance = 0;
        boolean seenFun = false;
        int index = startIndex;
        while (index < lines.size()) {
            String line = lines.get(index).trim();
            if (line.startsWith("//")) {
                index++;
                continue;
            }
            builder.append(line).append(' ');
            if (!seenFun && line.contains("fun")) {
                seenFun = true;
            }
            parenBalance += countOutsideQuotes(line, '(') - countOutsideQuotes(line, ')');
            if (seenFun && parenBalance <= 0) {
                break;
            }
            if (seenFun && line.contains("{")) {
                break;
            }
            index++;
        }

        String signature = builder.toString().trim();
        if (!signature.contains("fun")) {
            return null;
        }

        Matcher matcher = FUN_PATTERN.matcher(signature);
        if (!matcher.find()) {
            return null;
        }

        String name = matcher.group(1);
        int paramsStart = signature.indexOf('(', matcher.start());
        if (paramsStart < 0) {
            return null;
        }
        int paramsEnd = findClosing(signature, paramsStart, '(', ')');
        if (paramsEnd < 0) {
            return null;
        }

        String parameters = signature.substring(paramsStart + 1, paramsEnd);
        String returnType = "Unit";
        int colonIndex = signature.indexOf(':', paramsEnd + 1);
        if (colonIndex >= 0) {
            int endIndex = findReturnTypeEnd(signature, colonIndex + 1);
            if (endIndex > colonIndex) {
                returnType = signature.substring(colonIndex + 1, endIndex).trim();
            }
        }

        return new Signature(name, parameters, returnType);
    }

    private static int countOutsideQuotes(String line, char target) {
        boolean inSingle = false;
        boolean inDouble = false;
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            if (!inSingle && !inDouble && c == target) {
                count++;
            }
        }
        return count;
    }

    private static int findClosing(String text, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findReturnTypeEnd(String signature, int start) {
        int index = start;
        boolean inGeneric = false;
        while (index < signature.length()) {
            char c = signature.charAt(index);
            if (c == '<') {
                inGeneric = true;
            } else if (c == '>') {
                inGeneric = false;
            }
            if (!inGeneric && (c == '=' || c == '{')) {
                break;
            }
            if (!inGeneric && Character.isWhitespace(c)) {
                int lookAhead = index + 1;
                while (lookAhead < signature.length() && Character.isWhitespace(signature.charAt(lookAhead))) {
                    lookAhead++;
                }
                if (lookAhead < signature.length()) {
                    char next = signature.charAt(lookAhead);
                    if (next == '=' || next == '{') {
                        break;
                    }
                }
            }
            index++;
        }
        return index;
    }

    private static AnnotationParseResult parseAnnotation(List<String> lines, int startIndex) {
        StringBuilder builder = new StringBuilder();
        int balance = 0;
        int index = startIndex;
        boolean firstLine = true;
        while (index < lines.size()) {
            String trimmed = lines.get(index).trim();
            if (firstLine && !trimmed.startsWith("@")) {
                break;
            }
            if (!trimmed.isEmpty()) {
                if (!firstLine) {
                    builder.append(' ');
                }
                builder.append(trimmed);
                balance += countOutsideQuotes(trimmed, '(') - countOutsideQuotes(trimmed, ')');
                if (balance <= 0) {
                    break;
                }
            }
            index++;
            firstLine = false;
        }

        Optional<KotlinAnnotation> annotation = parseKotlinAnnotation(builder.toString());
        return new AnnotationParseResult(annotation, index);
    }

    private static Optional<KotlinAnnotation> parseKotlinAnnotation(String raw) {
        if (raw == null || raw.isEmpty() || !raw.startsWith("@")) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        trimmed = trimmed.substring(1).trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        String name = trimmed;
        String args = "";
        int parenIndex = trimmed.indexOf('(');
        if (parenIndex >= 0) {
            name = trimmed.substring(0, parenIndex).trim();
            int endIndex = trimmed.lastIndexOf(')');
            if (endIndex >= 0 && endIndex > parenIndex) {
                args = trimmed.substring(parenIndex + 1, endIndex).trim();
            } else {
                args = trimmed.substring(parenIndex + 1).trim();
            }
        }

        if (name.isEmpty()) {
            return Optional.empty();
        }

        Map<String, KotlinAttribute> attributes = parseAttributeMap(args);
        return Optional.of(new KotlinAnnotation(name, attributes, raw));
    }

    private static Map<String, KotlinAttribute> parseAttributeMap(String args) {
        if (args == null || args.isBlank()) {
            return Collections.emptyMap();
        }

        List<String> segments = splitTopLevel(args, ',');
        Map<String, KotlinAttribute> attributes = new LinkedHashMap<>();
        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            int equalsIndex = findTopLevelEquals(segment);
            String key;
            String value;
            if (equalsIndex >= 0) {
                key = segment.substring(0, equalsIndex).trim();
                value = segment.substring(equalsIndex + 1).trim();
                if (key.isEmpty()) {
                    key = "value";
                }
            } else {
                key = "value";
                value = segment.trim();
            }

            if (value.isEmpty()) {
                continue;
            }

            attributes.putIfAbsent(key, new KotlinAttribute(value, isCollectionValue(value)));
        }
        return attributes;
    }

    private static int findTopLevelEquals(String text) {
        boolean inSingle = false;
        boolean inDouble = false;
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        int angle = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            if (inSingle || inDouble) {
                continue;
            }
            switch (c) {
                case '(' -> paren++;
                case ')' -> paren--;
                case '{' -> brace++;
                case '}' -> brace--;
                case '[' -> bracket++;
                case ']' -> bracket--;
                case '<' -> angle++;
                case '>' -> angle--;
                default -> {
                }
            }
            if (c == '=' && paren == 0 && brace == 0 && bracket == 0 && angle == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String text, char delimiter) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        int angle = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }

            if (!inSingle && !inDouble) {
                switch (c) {
                    case '(' -> paren++;
                    case ')' -> paren--;
                    case '{' -> brace++;
                    case '}' -> brace--;
                    case '[' -> bracket++;
                    case ']' -> bracket--;
                    case '<' -> angle++;
                    case '>' -> angle--;
                    default -> {
                    }
                }
                if (c == delimiter && paren == 0 && brace == 0 && bracket == 0 && angle == 0) {
                    parts.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }

            current.append(c);
        }

        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }

        return parts;
    }

    private static Set<Parameter> parseParameters(String packageAndClassName, String parameterSection) {
        if (parameterSection == null || parameterSection.isBlank()) {
            return new LinkedHashSet<>();
        }

        List<String> segments = splitTopLevel(parameterSection, ',');
        Set<Parameter> parameters = new LinkedHashSet<>();
        for (String segment : segments) {
            String trimmed = stripLeadingAnnotations(segment.trim());
            if (trimmed.isEmpty()) {
                continue;
            }
            int equalsIndex = findTopLevelEquals(trimmed);
            if (equalsIndex >= 0) {
                trimmed = trimmed.substring(0, equalsIndex).trim();
            }
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("var ") || trimmed.startsWith("val ")) {
                trimmed = trimmed.substring(4).trim();
            }

            int colonIndex = trimmed.indexOf(':');
            if (colonIndex < 0) {
                continue;
            }

            String name = trimmed.substring(0, colonIndex).trim();
            String type = trimmed.substring(colonIndex + 1).trim();
            if (type.isEmpty()) {
                type = "Any";
            }

            parameters.add(new Parameter(name, packageAndClassName, type, Collections.emptySet()));
        }

        return parameters;
    }

    private static String stripLeadingAnnotations(String text) {
        String remaining = text.trim();
        while (remaining.startsWith("@")) {
            int index = 1;
            while (index < remaining.length() && (Character.isJavaIdentifierPart(remaining.charAt(index)) || remaining.charAt(index) == '.')) {
                index++;
            }
            while (index < remaining.length() && Character.isWhitespace(remaining.charAt(index))) {
                index++;
            }
            if (index < remaining.length() && remaining.charAt(index) == '(') {
                int balance = 1;
                index++;
                while (index < remaining.length() && balance > 0) {
                    char c = remaining.charAt(index);
                    if (c == '(') {
                        balance++;
                    } else if (c == ')') {
                        balance--;
                    }
                    index++;
                }
            }
            while (index < remaining.length() && Character.isWhitespace(remaining.charAt(index))) {
                index++;
            }
            remaining = index >= remaining.length() ? "" : remaining.substring(index).trim();
        }
        return remaining;
    }

    private static Optional<KotlinAnnotation> findMappingAnnotation(List<KotlinAnnotation> annotations) {
        return annotations.stream()
                .filter(annotation -> EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(annotation.simpleName()))
                .findFirst();
    }

    private static ClassRole determineClassRole(List<KotlinAnnotation> annotations) {
        for (KotlinAnnotation annotation : annotations) {
            ClassRole direct = SourceToObjectUtils.mapAnnotationToRole(annotation.simpleName());
            if (!ClassRole.UNKNOWN.equals(direct)) {
                return direct;
            }
            ClassRole qualified = SourceToObjectUtils.mapAnnotationToRole(annotation.qualifiedName());
            if (!ClassRole.UNKNOWN.equals(qualified)) {
                return qualified;
            }
        }
        return ClassRole.UNKNOWN;
    }

    private static boolean isCollectionValue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            if ((trimmed.startsWith("[") && trimmed.endsWith("]")) || (trimmed.startsWith("{") && trimmed.endsWith("}"))) {
                return true;
            }
        }
        return trimmed.startsWith("arrayOf(") || trimmed.startsWith("listOf(") || trimmed.startsWith("setOf(")
                || trimmed.startsWith("mutableListOf(") || trimmed.startsWith("mutableSetOf(");
    }

    private static String sanitizeAttributeValue(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return value;
        }

        while ((value.startsWith("[") && value.endsWith("]")) || (value.startsWith("{") && value.endsWith("}"))) {
            value = value.substring(1, value.length() - 1).trim();
        }

        if (value.startsWith("arrayOf(") || value.startsWith("listOf(") || value.startsWith("setOf(")
                || value.startsWith("mutableListOf(") || value.startsWith("mutableSetOf(")) {
            int start = value.indexOf('(');
            int end = value.lastIndexOf(')');
            if (start >= 0 && end > start) {
                value = value.substring(start + 1, end).trim();
            }
        }

        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    private static List<String> extractElements(String raw) {
        if (raw == null) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        if ((trimmed.startsWith("[") && trimmed.endsWith("]")) || (trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        } else if (trimmed.startsWith("arrayOf(") || trimmed.startsWith("listOf(") || trimmed.startsWith("setOf(")
                || trimmed.startsWith("mutableListOf(") || trimmed.startsWith("mutableSetOf(")) {
            int start = trimmed.indexOf('(');
            int end = trimmed.lastIndexOf(')');
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start + 1, end).trim();
            }
        }

        if (trimmed.isEmpty()) {
            return List.of();
        }

        return splitTopLevel(trimmed, ',').stream()
                .map(String::trim)
                .filter(segment -> !segment.isEmpty())
                .collect(Collectors.toList());
    }

    private static Expression parseSingleExpression(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            try {
                return StaticJavaParser.parseExpression("\"\"");
            } catch (Exception e) {
                return new StringLiteralExpr("");
            }
        }

        if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
            trimmed = "\"" + escape(trimmed.substring(1, trimmed.length() - 1)) + "\"";
        }

        try {
            return StaticJavaParser.parseExpression(trimmed);
        } catch (Exception e) {
            try {
                String fallback = "\"" + escape(sanitizeAttributeValue(value)) + "\"";
                return StaticJavaParser.parseExpression(fallback);
            } catch (Exception ignored) {
                return new StringLiteralExpr(sanitizeAttributeValue(value));
            }
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record AnnotationParseResult(Optional<KotlinAnnotation> annotation, int nextIndex) {
    }

    private record Signature(String name, String parameters, String returnType) {
    }

    private record KotlinAnnotation(String qualifiedName, Map<String, KotlinAttribute> attributes, String raw) {

        KotlinAnnotation {
            attributes = attributes == null ? Collections.emptyMap() : new LinkedHashMap<>(attributes);
        }

        String simpleName() {
            int dotIndex = qualifiedName.lastIndexOf('.');
            return dotIndex >= 0 ? qualifiedName.substring(dotIndex + 1) : qualifiedName;
        }

        Annotation toModel(String packageAndClassName) {
            Map<String, String> attributeValues = attributes.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().sanitized(),
                            (existing, replacement) -> replacement, LinkedHashMap::new));
            return new Annotation(simpleName(), packageAndClassName, new HashMap<>(attributeValues));
        }

        Optional<AnnotationExpr> toAnnotationExpr() {
            try {
                if (attributes.isEmpty()) {
                    return Optional.of(new MarkerAnnotationExpr(simpleName()));
                }

                if (attributes.size() == 1 && attributes.containsKey("value") && !attributes.get("value").isCollection()) {
                    Expression expression = attributes.get("value").toExpression();
                    return Optional.of(new SingleMemberAnnotationExpr(new Name(simpleName()), expression));
                }

                NodeList<MemberValuePair> pairs = new NodeList<>();
                for (Map.Entry<String, KotlinAttribute> entry : attributes.entrySet()) {
                    Expression expression = entry.getValue().toExpression();
                    pairs.add(new MemberValuePair(entry.getKey(), expression));
                }

                return Optional.of(new NormalAnnotationExpr(new Name(simpleName()), pairs));
            } catch (Exception e) {
                LoggerManager.debug(() -> "Unable to translate Kotlin annotation " + raw + " into Java expression: " + e.getMessage());
                return Optional.empty();
            }
        }
    }

    private static final class KotlinAttribute {
        private final String raw;
        private final boolean collection;

        KotlinAttribute(String raw, boolean collection) {
            this.raw = raw;
            this.collection = collection;
        }

        boolean isCollection() {
            return collection;
        }

        String sanitized() {
            if (collection) {
                return extractElements(raw).stream()
                        .map(KotlinSourceParser::sanitizeAttributeValue)
                        .collect(Collectors.joining(", "));
            }
            return sanitizeAttributeValue(raw);
        }

        Expression toExpression() {
            if (collection) {
                NodeList<Expression> expressions = new NodeList<>();
                for (String element : extractElements(raw)) {
                    expressions.add(parseSingleExpression(element));
                }
                return new ArrayInitializerExpr(expressions);
            }
            return parseSingleExpression(raw);
        }
    }
}
