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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        HttpMethod finalHttpMethod = HttpMethod.ALL;

        String preUrl = extractMappingPath(requestMapping).orElse("");

        String url = "";
        if (endpointMapping instanceof NormalAnnotationExpr nae) {
            for (MemberValuePair pair : nae.getPairs()) {
                String pairName = pair.getNameAsString();
                if (pairName.equals("method")) {
                    List<String> methods = extractMethodNames(pair.getValue());
                    if (!methods.isEmpty()) {
                        finalHttpMethod = mergeHttpMethods(methods);
                    }
                } else if (pairName.equals("path") || pairName.equals("value")) {
                    url = extractStringValue(pair.getValue()).orElse(url);
                }
            }
        } else if (endpointMapping instanceof SingleMemberAnnotationExpr smae) {
            url = extractStringValue(smae.getMemberValue()).orElse("");
        } else if (endpointMapping instanceof MarkerAnnotationExpr) {
            if (preUrl.isEmpty()) {
                url = "/";
            }
        }

        if (finalHttpMethod == HttpMethod.ALL) {
            finalHttpMethod = httpFromMapping(resolvedMappingName);
        }

        preUrl = normalizePath(preUrl);
        url = normalizePath(url);

        String finalURL;
        if (preUrl.isEmpty() && url.isEmpty()) {
            finalURL = "/";
        } else {
            finalURL = preUrl + url;
        }

        finalURL = finalURL.replaceAll("//", "/");
        finalURL = finalURL.endsWith("/") && !finalURL.equals("/") ? finalURL.substring(0, finalURL.length() - 1) : finalURL;

        this.httpMethod = finalHttpMethod;
        this.name = resolvedMappingName;
        this.url = simplifyEndpointURL(finalURL);
    }


    /**
     * Method to get http method from mapping
     * 
     * @param mapping mapping string for a given method
     * @return HttpMethod object of same method type
     */
    private static Optional<String> extractMappingPath(AnnotationExpr annotationExpr) {
        if (annotationExpr == null) {
            return Optional.empty();
        }

        if (annotationExpr instanceof NormalAnnotationExpr nae) {
            for (MemberValuePair pair : nae.getPairs()) {
                String pairName = pair.getNameAsString();
                if (pairName.equals("path") || pairName.equals("value")) {
                    return extractStringValue(pair.getValue());
                }
            }
            return Optional.empty();
        }

        if (annotationExpr instanceof SingleMemberAnnotationExpr smae) {
            return extractStringValue(smae.getMemberValue());
        }

        if (annotationExpr instanceof MarkerAnnotationExpr) {
            return Optional.of("");
        }

        return Optional.empty();
    }

    private static Optional<String> extractStringValue(Expression expression) {
        if (expression == null) {
            return Optional.empty();
        }

        if (expression.isStringLiteralExpr()) {
            return Optional.of(expression.asStringLiteralExpr().asString());
        }
        if (expression.isCharLiteralExpr()) {
            CharLiteralExpr literal = expression.asCharLiteralExpr();
            return Optional.of(String.valueOf(literal.getValue()));
        }
        if (expression.isArrayInitializerExpr()) {
            ArrayInitializerExpr array = expression.asArrayInitializerExpr();
            for (Expression value : array.getValues()) {
                Optional<String> nested = extractStringValue(value);
                if (nested.isPresent()) {
                    return nested;
                }
            }
            return Optional.empty();
        }
        if (expression.isNameExpr()) {
            return Optional.of(expression.asNameExpr().toString());
        }
        if (expression.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccessExpr = expression.asFieldAccessExpr();
            return Optional.of(fieldAccessExpr.toString());
        }
        if (expression.isMethodCallExpr()) {
            MethodCallExpr methodCallExpr = expression.asMethodCallExpr();
            return Optional.of(methodCallExpr.toString());
        }
        return Optional.of(expression.toString().replace("\"", ""));
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

    private static HttpMethod mergeHttpMethods(List<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return HttpMethod.ALL;
        }

        HttpMethod first = httpFromMapping(methods.get(0));
        if (methods.size() == 1) {
            return first;
        }

        for (int i = 1; i < methods.size(); i++) {
            HttpMethod next = httpFromMapping(methods.get(i));
            if (!next.equals(first)) {
                return HttpMethod.ALL;
            }
        }

        return first;
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
