package edu.university.ecs.lab.common.models.enums;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import edu.university.ecs.lab.intermediate.utils.StringParserUtils;
import lombok.Getter;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Factory class for generating an endpoint template from annotations
 */
@Getter
public class EndpointTemplate {
    public static final Set<String> ENDPOINT_ANNOTATIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "RequestMapping",
            "GetMapping",
            "PutMapping",
            "PostMapping",
            "DeleteMapping",
            "PatchMapping"
    )));
    private final HttpMethod httpMethod;
    private final String name;
    private final String url;



    public EndpointTemplate(AnnotationExpr requestMapping, AnnotationExpr endpointMapping) {
        this(requestMapping, endpointMapping, endpointMapping.getNameAsString());
    }

    public EndpointTemplate(AnnotationExpr requestMapping, AnnotationExpr endpointMapping, String resolvedMappingName) {
        List<EndpointTemplate> expanded = from(requestMapping, endpointMapping, resolvedMappingName);
        EndpointTemplate fallback = expanded.isEmpty()
                ? new EndpointTemplate(HttpMethod.ALL, resolvedMappingName, "/")
                : expanded.get(0);
        this.httpMethod = fallback.httpMethod;
        this.name = fallback.name;
        this.url = fallback.url;
    }

    private EndpointTemplate(HttpMethod httpMethod, String name, String url) {
        this.httpMethod = httpMethod;
        this.name = name;
        this.url = url;
    }

    public static List<EndpointTemplate> from(AnnotationExpr requestMapping, AnnotationExpr endpointMapping, String resolvedMappingName) {
        List<String> classPaths = extractMappingPaths(requestMapping);
        if (classPaths.isEmpty()) {
            classPaths = List.of("");
        }

        List<String> methodPaths = extractMappingPaths(endpointMapping);
        if (methodPaths.isEmpty()) {
            methodPaths = List.of("");
        }

        List<HttpMethod> httpMethods = extractHttpMethods(endpointMapping, resolvedMappingName);
        if (httpMethods.isEmpty()) {
            httpMethods = List.of(HttpMethod.ALL);
        }

        Map<String, EndpointTemplate> templates = new LinkedHashMap<>();
        for (String classPath : classPaths) {
            String normalizedBase = normalizePath(classPath);
            for (String methodPath : methodPaths) {
                String normalizedMethod = normalizePath(methodPath);
                String combined;
                if (normalizedBase.isEmpty() && normalizedMethod.isEmpty()) {
                    combined = "/";
                } else {
                    combined = normalizedBase + normalizedMethod;
                }
                combined = combined.replaceAll("/+", "/");
                if (combined.endsWith("/") && !combined.equals("/")) {
                    combined = combined.substring(0, combined.length() - 1);
                }
                String simplified = simplifyEndpointURL(combined);
                for (HttpMethod method : httpMethods) {
                    String key = method.name() + " " + simplified;
                    templates.putIfAbsent(key, new EndpointTemplate(method, resolvedMappingName, simplified));
                }
            }
        }

        return new ArrayList<>(templates.values());
    }

    private static List<String> extractMappingPaths(AnnotationExpr annotationExpr) {
        if (annotationExpr == null) {
            return List.of();
        }

        if (annotationExpr instanceof NormalAnnotationExpr nae) {
            List<String> values = new ArrayList<>();
            for (MemberValuePair pair : nae.getPairs()) {
                String pairName = pair.getNameAsString();
                if (pairName.equals("path") || pairName.equals("value")) {
                    values.addAll(extractStringValues(pair.getValue()));
                }
            }
            return values;
        }

        if (annotationExpr instanceof SingleMemberAnnotationExpr smae) {
            return extractStringValues(smae.getMemberValue());
        }

        if (annotationExpr instanceof MarkerAnnotationExpr) {
            return List.of("");
        }

        return List.of();
    }

    private static List<HttpMethod> extractHttpMethods(AnnotationExpr annotationExpr, String resolvedMappingName) {
        List<String> methodNames = new ArrayList<>();
        if (annotationExpr instanceof NormalAnnotationExpr nae) {
            for (MemberValuePair pair : nae.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    methodNames.addAll(extractMethodNames(pair.getValue()));
                }
            }
        }

        List<HttpMethod> methods = methodNames.stream()
                .map(EndpointTemplate::httpFromMapping)
                .filter(httpMethod -> httpMethod != null)
                .collect(Collectors.toList());

        if (!methods.isEmpty()) {
            return methods;
        }

        HttpMethod derived = httpFromMapping(resolvedMappingName);
        return derived == null ? List.of() : List.of(derived);
    }

    private static List<String> extractStringValues(Expression expression) {
        if (expression == null) {
            return List.of();
        }

        if (expression.isArrayInitializerExpr()) {
            ArrayInitializerExpr array = expression.asArrayInitializerExpr();
            return array.getValues().stream()
                    .flatMap(value -> extractStringValues(value).stream())
                    .collect(Collectors.toList());
        }

        if (expression.isStringLiteralExpr()) {
            return List.of(expression.asStringLiteralExpr().asString());
        }

        if (expression.isCharLiteralExpr()) {
            CharLiteralExpr literal = expression.asCharLiteralExpr();
            return List.of(String.valueOf(literal.getValue()));
        }

        if (expression.isNameExpr()) {
            return List.of(expression.asNameExpr().toString());
        }

        if (expression.isFieldAccessExpr()) {
            return List.of(expression.asFieldAccessExpr().toString());
        }

        if (expression.isMethodCallExpr()) {
            return List.of(expression.asMethodCallExpr().toString());
        }

        return List.of(stripQuotes(expression.toString()).replace("\"", ""));
    }

    private static List<String> extractMethodNames(Expression expression) {
        if (expression == null) {
            return List.of();
        }

        if (expression.isArrayInitializerExpr()) {
            ArrayInitializerExpr array = expression.asArrayInitializerExpr();
            return array.getValues().stream()
                    .flatMap(value -> extractMethodNames(value).stream())
                    .collect(Collectors.toList());
        }

        if (expression.isNameExpr()) {
            return List.of(expression.asNameExpr().toString());
        }
        if (expression.isFieldAccessExpr()) {
            return List.of(expression.asFieldAccessExpr().toString());
        }
        if (expression.isStringLiteralExpr()) {
            return List.of(expression.asStringLiteralExpr().asString());
        }
        if (expression.isMethodCallExpr()) {
            return List.of(expression.asMethodCallExpr().toString());
        }

        return expression.toString().isEmpty() ? List.of() : List.of(expression.toString());
    }

    private static String normalizePath(String candidate) {
        if (candidate == null) {
            return "";
        }

        String normalized = stripQuotes(candidate.trim());
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.equals("/")) {
            return normalized;
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static HttpMethod httpFromMapping(String mapping) {
        switch (mapping) {
            case "GetMapping":
            case "RequestMethod.GET":
            case "GET":
                return HttpMethod.GET;
            case "PostMapping":
            case "RequestMethod.POST":
            case "POST":
                return HttpMethod.POST;
            case "DeleteMapping":
            case "RequestMethod.DELETE":
            case "DELETE":
                return HttpMethod.DELETE;
            case "PutMapping":
            case "RequestMethod.PUT":
            case "PUT":
                return HttpMethod.PUT;
            case "PatchMapping":
            case "RequestMethod.PATCH":
            case "PATCH":
                return HttpMethod.PATCH;
            default:
                return HttpMethod.ALL;
        }

    }

    /**
     * Method to get endpoint path from annotations
     * 
     * @param ae annotation expression from method
     * @param url string formatted as a url
     * @return endpoint path/url from annotation expression
     */
    public static String getPathFromAnnotation(AnnotationExpr ae, String url) {
        // Annotations of type @Mapping("/endpoint")
        if (ae.isSingleMemberAnnotationExpr()) {
            url = url + StringParserUtils.simplifyEndpointURL(
                    StringParserUtils.removeOuterQuotations(
                            ae.asSingleMemberAnnotationExpr().getMemberValue().toString()));
        }

        // Annotations of type @Mapping(path="/endpoint")
        else if (ae.isNormalAnnotationExpr() && !ae.asNormalAnnotationExpr().getPairs().isEmpty()) {
            for (MemberValuePair mvp : ae.asNormalAnnotationExpr().getPairs()) {
                if (mvp.getName().toString().equals("path") || mvp.getName().toString().equals("value")) {
                    url = url + StringParserUtils.simplifyEndpointURL(
                            StringParserUtils.removeOuterQuotations(mvp.getValue().toString()));
                    break;
                }
            }
        }
        return url;
    }

    /**
     * Simplifies all path arguments to {?}.
     *
     * @param url the endpoint URL
     * @return the simplified endpoint URL
     */
    public static String simplifyEndpointURL(String url) {
        return url.replaceAll("\\{[^{}]*\\}", "{?}");
    }


}
