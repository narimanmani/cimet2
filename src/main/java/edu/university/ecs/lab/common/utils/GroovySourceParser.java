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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lightweight parser that extracts Spring endpoints from Groovy controllers.
 */
final class GroovySourceParser {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^package\\s+([\\w\\.]+)");
    private static final Pattern CLASS_PATTERN = Pattern.compile("^(?:public|protected|private)?\\s*(?:abstract\\s+)?class\\s+([A-Za-z0-9_]+)");
    private static final Set<String> CONTROL_KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "try", "else", "do");

    private GroovySourceParser() {
    }

    static JClass parse(File sourceFile, Config config, String microserviceName) {
        List<String> lines;
        try {
            lines = Files.readAllLines(sourceFile.toPath());
        } catch (IOException e) {
            LoggerManager.warn(() -> "Failed to read Groovy source " + sourceFile.getPath() + ": " + e.getMessage());
            return null;
        }

        if (lines.isEmpty()) {
            return null;
        }

        String packageName = extractPackage(lines);
        String className = sourceFile.getName().replaceAll("\\.groovy$", "");
        String packageAndClassName = packageName.isEmpty() ? className : packageName + "." + className;
        String gitPath = FileUtils.localPathToGitPath(sourceFile.getPath(), config.getRepoName());

        List<GroovyAnnotation> pendingAnnotations = new ArrayList<>();
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
                // Package already captured from the first occurrence.
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
                    className = matcher.group(1);
                    packageAndClassName = packageName.isEmpty() ? className : packageName + "." + className;

                    final String fqcnForClassAnnotations = packageAndClassName;
                    classAnnotations = pendingAnnotations.stream()
                            .map(annotation -> annotation.toModel(fqcnForClassAnnotations))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    classRole = determineClassRole(pendingAnnotations);
                    classRequestMapping = findMappingAnnotation(pendingAnnotations)
                            .flatMap(GroovyAnnotation::toAnnotationExpr);
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

            if (!trimmed.contains("(")) {
                continue;
            }

            int parenIndex = trimmed.indexOf('(');
            if (parenIndex < 0) {
                continue;
            }
            String beforeParen = trimmed.substring(0, parenIndex).trim();
            if (beforeParen.isEmpty()) {
                continue;
            }

            String[] tokens = beforeParen.split("\\s+");
            if (tokens.length == 0) {
                continue;
            }

            String potentialName = tokens[tokens.length - 1];
            if (!potentialName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue;
            }
            if (CONTROL_KEYWORDS.contains(potentialName)) {
                continue;
            }

            boolean hasOpeningBrace = trimmed.substring(parenIndex).contains("{");
            if (!hasOpeningBrace) {
                int lookAhead = i + 1;
                while (lookAhead < lines.size()) {
                    String aheadTrimmed = lines.get(lookAhead).trim();
                    if (aheadTrimmed.isEmpty()) {
                        lookAhead++;
                        continue;
                    }
                    hasOpeningBrace = aheadTrimmed.startsWith("{");
                    break;
                }
            }
            if (!hasOpeningBrace) {
                continue;
            }

            List<GroovyAnnotation> methodAnnotations = new ArrayList<>(pendingAnnotations);
            pendingAnnotations.clear();

            final String fqcnForMethodAnnotations = packageAndClassName;
            Set<Annotation> methodAnnotationModels = methodAnnotations.stream()
                    .map(annotation -> annotation.toModel(fqcnForMethodAnnotations))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            String methodName = potentialName;
            String returnType = deriveReturnType(tokens);
            String parameterSection = extractParameterSection(trimmed, parenIndex);
            Set<Parameter> parameters = parseParameters(packageAndClassName, parameterSection);

            Method method = new Method(methodName, packageAndClassName, parameters, returnType, methodAnnotationModels,
                    microserviceName, className);

            Optional<GroovyAnnotation> mappingAnnotation = findMappingAnnotation(methodAnnotations);
            if (mappingAnnotation.isPresent()) {
                Optional<AnnotationExpr> mappingExpr = mappingAnnotation.get().toAnnotationExpr();
                if (mappingExpr.isPresent()) {
                    EndpointTemplate template = new EndpointTemplate(classRequestMapping.orElse(null), mappingExpr.get(),
                            mappingAnnotation.get().simpleName());
                    method = new Endpoint(method, template.getUrl(), template.getHttpMethod());
                    discoveredEndpoint = true;
                }
            }

            methods.add(method);
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

        Optional<GroovyAnnotation> annotation = parseGroovyAnnotation(builder.toString());
        return new AnnotationParseResult(annotation, index);
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

    private static Optional<GroovyAnnotation> parseGroovyAnnotation(String raw) {
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

        Map<String, String> attributes = parseAttributeMap(args);
        return Optional.of(new GroovyAnnotation(name, attributes, raw));
    }

    private static Map<String, GroovyAttribute> parseAttributeMap(String args) {
        if (args == null || args.isBlank()) {
            return Collections.emptyMap();
        }

        List<String> segments = splitTopLevel(args, ',');
        Map<String, GroovyAttribute> attributes = new LinkedHashMap<>();
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

            attributes.putIfAbsent(key, new GroovyAttribute(value, isCollectionValue(value)));
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

        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    private static String deriveReturnType(String[] tokens) {
        if (tokens.length <= 1) {
            return "Object";
        }

        List<String> parts = new ArrayList<>();
        for (int i = 0; i < tokens.length - 1; i++) {
            String token = tokens[i];
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.equals("public") || lower.equals("private") || lower.equals("protected") || lower.equals("static")
                    || lower.equals("final") || lower.equals("synchronized") || lower.equals("abstract")) {
                continue;
            }
            if (lower.equals("def")) {
                return "Object";
            }
            parts.add(token);
        }

        if (parts.isEmpty()) {
            return "Object";
        }

        return String.join(" ", parts);
    }

    private static String extractParameterSection(String line, int parenIndex) {
        int end = line.indexOf(')', parenIndex);
        if (end < 0) {
            return "";
        }
        return line.substring(parenIndex + 1, end);
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

            String[] tokens = trimmed.split("\\s+");
            if (tokens.length == 0) {
                continue;
            }

            String name = tokens[tokens.length - 1];
            String type = "Object";
            if (tokens.length > 1) {
                List<String> typeTokens = new ArrayList<>();
                for (int i = 0; i < tokens.length - 1; i++) {
                    String token = tokens[i];
                    String lower = token.toLowerCase(Locale.ROOT);
                    if (lower.equals("final") || lower.equals("def")) {
                        continue;
                    }
                    typeTokens.add(token.replace("...", "[]"));
                }
                if (!typeTokens.isEmpty()) {
                    type = String.join(" ", typeTokens);
                }
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

    private static Optional<GroovyAnnotation> findMappingAnnotation(List<GroovyAnnotation> annotations) {
        return annotations.stream()
                .filter(annotation -> EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(annotation.simpleName()))
                .findFirst();
    }

    private static ClassRole determineClassRole(List<GroovyAnnotation> annotations) {
        for (GroovyAnnotation annotation : annotations) {
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

    private record AnnotationParseResult(Optional<GroovyAnnotation> annotation, int nextIndex) {
    }

    private record GroovyAnnotation(String qualifiedName, Map<String, GroovyAttribute> attributes, String raw) {

        GroovyAnnotation {
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
                for (Map.Entry<String, GroovyAttribute> entry : attributes.entrySet()) {
                    Expression expression = entry.getValue().toExpression();
                    pairs.add(new MemberValuePair(entry.getKey(), expression));
                }

                return Optional.of(new NormalAnnotationExpr(new Name(simpleName()), pairs));
            } catch (Exception e) {
                LoggerManager.debug(() -> "Unable to translate Groovy annotation " + raw + " into Java expression: " + e.getMessage());
                return Optional.empty();
            }
        }
    }

    private static final class GroovyAttribute {
        private final String raw;
        private final boolean collection;

        GroovyAttribute(String raw, boolean collection) {
            this.raw = raw;
            this.collection = collection;
        }

        boolean isCollection() {
            return collection;
        }

        String sanitized() {
            if (collection) {
                return elements().stream()
                        .map(GroovySourceParser::sanitizeAttributeValue)
                        .collect(Collectors.joining(", "));
            }
            return sanitizeAttributeValue(raw);
        }

        Expression toExpression() {
            if (collection) {
                NodeList<Expression> expressions = new NodeList<>();
                for (String element : elements()) {
                    expressions.add(parseSingleExpression(element));
                }
                return new ArrayInitializerExpr(expressions);
            }
            return parseSingleExpression(raw);
        }

        private List<String> elements() {
            if (!collection) {
                return List.of(raw);
            }
            String trimmed = unwrapDelimiters(raw.trim());
            if (trimmed.isEmpty()) {
                return List.of();
            }
            return splitTopLevel(trimmed, ',').stream()
                    .map(String::trim)
                    .filter(segment -> !segment.isEmpty())
                    .collect(Collectors.toList());
        }

        private Expression parseSingleExpression(String value) {
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

        private String unwrapDelimiters(String candidate) {
            if (candidate.length() >= 2) {
                if ((candidate.startsWith("[") && candidate.endsWith("]")) || (candidate.startsWith("{") && candidate.endsWith("}"))) {
                    return candidate.substring(1, candidate.length() - 1).trim();
                }
            }
            return candidate;
        }
    }

    private static boolean isCollectionValue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            return (trimmed.startsWith("[") && trimmed.endsWith("]")) || (trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
        return false;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
