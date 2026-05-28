package com.showdoc.plugin.openapi;

import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.javadoc.PsiDocTag;
import com.intellij.psi.util.PsiUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 独立的 API 解析器，用于将 Java Spring Controller 转换为符合 ShowDoc 要求的 Markdown 格式。
 * 不复用 SpringApiParser 的任何代码。
 */
public class OpenApiDocParser {

    public static class DocPageInfo {
        public String catalog;    // 目录
        public String title;      // 页面标题
        public String content;    // Markdown 内容

        public DocPageInfo(String catalog, String title, String content) {
            this.catalog = catalog;
            this.title = title;
            this.content = content;
        }
    }

    public static List<DocPageInfo> parse(PsiJavaFile javaFile) {
        List<DocPageInfo> pages = new ArrayList<>();
        PsiClass[] classes = javaFile.getClasses();

        for (PsiClass psiClass : classes) {
            if (psiClass.isEnum()) {
                DocPageInfo page = generateEnumDocPage(psiClass);
                if (page != null) {
                    pages.add(page);
                }
                continue;
            }

            boolean isController = hasAnnotation(psiClass, "org.springframework.web.bind.annotation.RestController") ||
                    hasAnnotation(psiClass, "org.springframework.stereotype.Controller");

            if (!isController) continue;

            String classPath = getMappingPath(psiClass, "org.springframework.web.bind.annotation.RequestMapping");
            String catalogName = getCatalogName(psiClass);

            PsiMethod[] methods = psiClass.getMethods();
            for (PsiMethod method : methods) {
                String methodAnnotation = getWebAnnotation(method);
                if (methodAnnotation != null) {
                    String methodPath = getMappingPath(method, methodAnnotation);
                    String httpMethod = getHttpMethod(methodAnnotation, method);

                    String fullPath = classPath + methodPath;
                    fullPath = fullPath.replaceAll("//+", "/");
                    if (!fullPath.startsWith("/")) {
                        fullPath = "/" + fullPath;
                    }

                    DocPageInfo page = generateDocPage(catalogName, fullPath, httpMethod, method);
                    if (page != null) {
                        pages.add(page);
                    }
                }
            }
        }
        return pages;
    }

    private static DocPageInfo generateDocPage(String catalog, String url, String httpMethod, PsiMethod method) {
        PsiDocComment docComment = method.getDocComment();
        String title = method.getName();
        String description = "";
        String remark = "";

        Map<String, String> javadocParams = new HashMap<>();

        if (docComment != null) {
            PsiElement[] descriptionElements = docComment.getDescriptionElements();
            StringBuilder descBuilder = new StringBuilder();
            for (PsiElement el : descriptionElements) {
                descBuilder.append(el.getText());
            }

            String fullDesc = descBuilder.toString().trim();
            if (!fullDesc.isEmpty()) {
                String[] lines = fullDesc.split("\\r?\\n");
                boolean foundTitle = false;
                StringBuilder remainingDesc = new StringBuilder();

                for (String line : lines) {
                    String trimmedLine = line.trim();
                    if (!foundTitle) {
                        if (trimmedLine.isEmpty()) continue;
                        title = trimmedLine;
                        foundTitle = true;
                    } else {
                        remainingDesc.append(trimmedLine).append("\n");
                    }
                }

                if (remainingDesc.length() > 0) {
                    description = remainingDesc.toString().trim();
                } else {
                    description = title; // Default description to title if no extra desc
                }
            } else {
                description = title;
            }

            for (PsiDocTag tag : docComment.getTags()) {
                String tagName = tag.getName();
                if ("param".equals(tagName)) {
                    PsiElement[] dataElements = tag.getDataElements();
                    if (dataElements.length > 0) {
                        String paramName = dataElements[0].getText();
                        StringBuilder pd = new StringBuilder();
                        for (int i = 1; i < dataElements.length; i++) {
                            pd.append(dataElements[i].getText()).append(" ");
                        }
                        javadocParams.put(paramName, pd.toString().trim());
                    }
                } else if ("remark".equals(tagName) || "remarks".equals(tagName)) {
                    StringBuilder rm = new StringBuilder();
                    for (PsiElement el : tag.getDataElements()) {
                        rm.append(el.getText()).append(" ");
                    }
                    remark = rm.toString().trim();
                }
            }
        }

        // Parse params and returns
        List<ParamInfo> paramInfos = parseParameters(method, javadocParams);
        List<ParamInfo> returnInfos = parseReturn(method);

        // Build Markdown page content
        StringBuilder sb = new StringBuilder();
        sb.append("**简要描述：**\n\n");
        if (description.startsWith("-") || description.startsWith("*") || description.contains("\n")) {
            sb.append(description).append("\n\n");
        } else {
            sb.append("- ").append(description).append("\n\n");
        }

        sb.append("**请求URL：**\n\n");
        sb.append("- `").append(url).append("`\n\n");

        sb.append("**请求方式：**\n\n");
        sb.append("- ").append(httpMethod.toUpperCase()).append("\n\n");

        sb.append("**参数：**\n\n");
        if (paramInfos.isEmpty()) {
            sb.append("无\n\n");
        } else {
            sb.append("|参数名|必选|类型|说明|\n");
            sb.append("|:---|:---|:---|:---|\n");
            for (ParamInfo p : paramInfos) {
                sb.append(String.format("|%s|%s|%s|%s|\n", p.name, p.required ? "是" : "否", p.type, p.description));
            }
            sb.append("\n");
        }

        // Return example
        PsiType returnType = method.getReturnType();
        if (returnType != null && !PsiType.VOID.equals(returnType)) {
            JsonElement jsonElement = buildJsonExample(returnType, method.getProject(), 0);
            if (jsonElement != null && !jsonElement.isJsonNull()) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                String jsonStr = gson.toJson(jsonElement);
                sb.append("**返回示例：**\n\n");
                sb.append("```json\n");
                sb.append(jsonStr).append("\n");
                sb.append("```\n\n");
            }
        }

        sb.append("**返回参数说明：**\n\n");
        if (returnInfos.isEmpty()) {
            sb.append("无\n\n");
        } else {
            sb.append("|参数名|类型|说明|\n");
            sb.append("|:---|:---|:---|\n");
            for (ParamInfo r : returnInfos) {
                sb.append(String.format("|%s|%s|%s|\n", r.name, r.type, r.description));
            }
            sb.append("\n");
        }

        if (!remark.isEmpty()) {
            sb.append("**备注：**\n\n");
            sb.append("- ").append(remark).append("\n");
        }

        return new DocPageInfo(catalog, title, sb.toString());
    }

    private static class ParamInfo {
        public String name;
        public boolean required;
        public String type;
        public String description;

        public ParamInfo(String name, boolean required, String type, String description) {
            this.name = name;
            this.required = required;
            this.type = type;
            this.description = description;
        }
    }

    private static List<ParamInfo> parseParameters(PsiMethod method, Map<String, String> javadocParams) {
        List<ParamInfo> paramDocs = new ArrayList<>();
        PsiParameter[] parameters = method.getParameterList().getParameters();

        for (PsiParameter p : parameters) {
            String typeName = p.getType().getPresentableText();
            if (typeName.contains("HttpServletRequest") || typeName.contains("HttpServletResponse") || typeName.contains("Model")) {
                continue;
            }

            PsiClass paramClass = PsiUtil.resolveClassInClassTypeOnly(p.getType());
            boolean isCustomObject = paramClass != null && !paramClass.getQualifiedName().startsWith("java.")
                    && !paramClass.getQualifiedName().startsWith("org.springframework.");

            if (isCustomObject) {
                extractParams(p.getType(), paramDocs, false, "", method.getProject(), 0);
            } else {
                String pName = p.getName();
                String pDesc = javadocParams.getOrDefault(pName, "");
                boolean isRequired = false;

                if (hasAnnotation(p, "org.springframework.web.bind.annotation.RequestParam")) {
                    PsiAnnotation ann = p.getAnnotation("org.springframework.web.bind.annotation.RequestParam");
                    PsiAnnotationMemberValue reqVal = ann != null ? ann.findAttributeValue("required") : null;
                    isRequired = (reqVal == null || !reqVal.getText().equals("false"));
                } else if (hasAnnotation(p, "org.springframework.web.bind.annotation.PathVariable")) {
                    isRequired = true;
                }

                paramDocs.add(new ParamInfo(pName, isRequired, typeName, pDesc));
            }
        }
        return paramDocs;
    }

    private static List<ParamInfo> parseReturn(PsiMethod method) {
        List<ParamInfo> returnDocs = new ArrayList<>();
        PsiType returnType = method.getReturnType();
        if (returnType == null || PsiType.VOID.equals(returnType)) {
            return returnDocs;
        }

        extractParams(returnType, returnDocs, true, "", method.getProject(), 0);
        return returnDocs;
    }

    private static JsonElement buildJsonExample(PsiType type, com.intellij.openapi.project.Project project, int depth) {
        if (depth > 4 || type == null) {
            return JsonNull.INSTANCE;
        }

        if (type instanceof PsiPrimitiveType) {
            if (PsiType.INT.equals(type) || PsiType.LONG.equals(type) || PsiType.SHORT.equals(type) || PsiType.BYTE.equals(type)) {
                return new JsonPrimitive(0);
            } else if (PsiType.DOUBLE.equals(type) || PsiType.FLOAT.equals(type)) {
                return new JsonPrimitive(0.0);
            } else if (PsiType.BOOLEAN.equals(type)) {
                return new JsonPrimitive(false);
            }
            return new JsonPrimitive("");
        }

        String typeText = type.getCanonicalText();
        if (typeText.startsWith("java.lang.String")) {
            return new JsonPrimitive("");
        }
        if (typeText.startsWith("java.lang.Integer") || typeText.startsWith("java.lang.Long") || typeText.startsWith("java.lang.Short")) {
            return new JsonPrimitive(0);
        }
        if (typeText.startsWith("java.lang.Double") || typeText.startsWith("java.lang.Float") || typeText.startsWith("java.math.BigDecimal")) {
            return new JsonPrimitive(0.0);
        }
        if (typeText.startsWith("java.lang.Boolean")) {
            return new JsonPrimitive(false);
        }
        if (typeText.startsWith("java.util.Date") || typeText.startsWith("java.time.")) {
            return new JsonPrimitive("2023-01-01 12:00:00");
        }

        if (type instanceof PsiArrayType) {
            JsonArray array = new JsonArray();
            array.add(buildJsonExample(((PsiArrayType) type).getComponentType(), project, depth + 1));
            return array;
        }

        if (type instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) type;
            PsiClassType.ClassResolveResult resolveResult = classType.resolveGenerics();
            PsiClass psiClass = resolveResult.getElement();
            if (psiClass == null) return JsonNull.INSTANCE;

            if (psiClass.isEnum()) {
                PsiField[] fields = psiClass.getFields();
                for (PsiField field : fields) {
                    if (field instanceof PsiEnumConstant) {
                        return new JsonPrimitive(field.getName());
                    }
                }
                return new JsonPrimitive("");
            }

            String qName = psiClass.getQualifiedName();
            if (qName != null) {
                if (qName.equals("reactor.core.publisher.Mono") ||
                        qName.equals("reactor.core.publisher.Flux") ||
                        qName.startsWith("java.util.concurrent.")) {
                    PsiType[] parameters = classType.getParameters();
                    if (parameters.length > 0) {
                        return buildJsonExample(parameters[0], project, depth);
                    }
                }

                if (qName.equals("java.util.List") || qName.equals("java.util.ArrayList") ||
                        qName.equals("java.util.Set") || qName.equals("java.util.HashSet") ||
                        qName.equals("java.util.Collection")) {
                    JsonArray array = new JsonArray();
                    PsiType[] parameters = classType.getParameters();
                    if (parameters.length > 0) {
                        array.add(buildJsonExample(parameters[0], project, depth + 1));
                    } else {
                        array.add(new JsonObject());
                    }
                    return array;
                }
                if (qName.startsWith("java.util.Map")) {
                    JsonObject map = new JsonObject();
                    map.add("key", new JsonPrimitive("value"));
                    return map;
                }

                if (qName.endsWith(".IPage") || qName.endsWith(".Page")) {
                    JsonObject pageObj = new JsonObject();
                    pageObj.add("current", new JsonPrimitive(1));
                    pageObj.add("size", new JsonPrimitive(10));
                    pageObj.add("total", new JsonPrimitive(100));
                    JsonArray records = new JsonArray();
                    PsiType[] parameters = classType.getParameters();
                    if (parameters.length > 0) {
                        records.add(buildJsonExample(parameters[0], project, depth + 1));
                    }
                    pageObj.add("records", records);
                    return pageObj;
                }
            }

            JsonObject obj = new JsonObject();
            PsiSubstitutor substitutor = resolveResult.getSubstitutor();

            if (psiClass.isRecord()) {
                PsiRecordComponent[] components = psiClass.getRecordComponents();
                for (PsiRecordComponent comp : components) {
                    PsiType compType = substitutor.substitute(comp.getType());
                    obj.add(comp.getName(), buildJsonExample(compType, project, depth + 1));
                }
            } else {
                PsiField[] fields = psiClass.getAllFields();
                for (PsiField field : fields) {
                    if (field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT)) {
                        continue;
                    }
                    PsiType fieldType = substitutor.substitute(field.getType());
                    obj.add(field.getName(), buildJsonExample(fieldType, project, depth + 1));
                }
            }
            return obj;
        }

        return JsonNull.INSTANCE;
    }

    private static void extractParams(PsiType type, List<ParamInfo> docs, boolean isReturn, String prefix, com.intellij.openapi.project.Project project, int depth) {
        if (depth > 4 || type == null) {
            return;
        }

        if (type instanceof PsiPrimitiveType) {
            return;
        }

        String typeText = type.getCanonicalText();
        if (typeText.startsWith("java.lang.") || typeText.startsWith("java.util.Date") || typeText.startsWith("java.time.") || typeText.startsWith("java.math.") || typeText.equals("org.springframework.web.multipart.MultipartFile")) {
            return;
        }

        if (type instanceof PsiArrayType) {
            extractParams(((PsiArrayType) type).getComponentType(), docs, isReturn, prefix, project, depth);
            return;
        }

        if (type instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) type;
            PsiClassType.ClassResolveResult resolveResult = classType.resolveGenerics();
            PsiClass psiClass = resolveResult.getElement();
            if (psiClass == null) return;

            if (psiClass.isEnum()) {
                return;
            }

            String qName = psiClass.getQualifiedName();
            if (qName != null) {
                if (qName.equals("java.util.List") || qName.equals("java.util.ArrayList") ||
                        qName.equals("java.util.Set") || qName.equals("java.util.HashSet") ||
                        qName.equals("java.util.Collection")) {
                    PsiType[] parameters = classType.getParameters();
                    if (parameters.length > 0) {
                        extractParams(parameters[0], docs, isReturn, prefix, project, depth);
                    }
                    return;
                }
                if (qName.startsWith("java.util.Map")) {
                    return;
                }

                if (qName.equals("reactor.core.publisher.Mono") ||
                        qName.equals("reactor.core.publisher.Flux") ||
                        qName.startsWith("java.util.concurrent.")) {
                    PsiType[] parameters = classType.getParameters();
                    if (parameters.length > 0) {
                        extractParams(parameters[0], docs, isReturn, prefix, project, depth);
                    }
                    return;
                }

                if (qName.endsWith(".IPage") || qName.endsWith(".Page")) {
                    if (isReturn) {
                        docs.add(new ParamInfo(prefix + "current", false, "long", "当前页"));
                        docs.add(new ParamInfo(prefix + "size", false, "long", "每页大小"));
                        docs.add(new ParamInfo(prefix + "total", false, "long", "总数"));
                        docs.add(new ParamInfo(prefix + "records", false, "array", "列表数据"));
                    } else {
                        docs.add(new ParamInfo(prefix + "current", false, "long", "当前页"));
                        docs.add(new ParamInfo(prefix + "size", false, "long", "每页大小"));
                    }

                    PsiType[] parameters = classType.getParameters();
                    if (parameters.length > 0) {
                        extractParams(parameters[0], docs, isReturn, prefix + "records.", project, depth + 1);
                    }
                    return;
                }
            }

            PsiSubstitutor substitutor = resolveResult.getSubstitutor();

            Map<String, String> fieldDocs = new HashMap<>();
            PsiDocComment docComment = psiClass.getDocComment();
            if (psiClass.isRecord() && docComment != null) {
                for (PsiDocTag tag : docComment.getTags()) {
                    if ("param".equals(tag.getName())) {
                        PsiElement[] dataElements = tag.getDataElements();
                        if (dataElements.length > 0) {
                            String compName = dataElements[0].getText();
                            StringBuilder desc = new StringBuilder();
                            for (int i = 1; i < dataElements.length; i++) {
                                desc.append(dataElements[i].getText()).append(" ");
                            }
                            fieldDocs.put(compName, desc.toString().trim());
                        }
                    }
                }
            }

            if (psiClass.isRecord()) {
                PsiRecordComponent[] components = psiClass.getRecordComponents();
                for (PsiRecordComponent comp : components) {
                    PsiType compType = substitutor.substitute(comp.getType());
                    String name = prefix + comp.getName();
                    String tName = getSimpleTypeName(compType);
                    String desc = fieldDocs.getOrDefault(comp.getName(), "");

                    docs.add(new ParamInfo(name, false, tName, desc));
                    extractParams(compType, docs, isReturn, name + ".", project, depth + 1);
                }
            } else {
                PsiField[] fields = psiClass.getAllFields();
                for (PsiField field : fields) {
                    if (field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT)) {
                        continue;
                    }
                    PsiType fieldType = substitutor.substitute(field.getType());
                    String name = prefix + field.getName();
                    String tName = getSimpleTypeName(fieldType);
                    String desc = getFieldDescription(field);

                    docs.add(new ParamInfo(name, false, tName, desc));
                    extractParams(fieldType, docs, isReturn, name + ".", project, depth + 1);
                }
            }
        }
    }

    private static String getSimpleTypeName(PsiType type) {
        if (type == null) return "object";
        if (type instanceof PsiArrayType) return "array";
        String presentableText = type.getPresentableText();
        if (presentableText.startsWith("List<") || presentableText.startsWith("Set<") || presentableText.startsWith("Collection<")) {
            return "array";
        }
        int genericIdx = presentableText.indexOf('<');
        if (genericIdx != -1) {
            presentableText = presentableText.substring(0, genericIdx);
        }
        return presentableText;
    }

    private static String getFieldDescription(PsiField field) {
        PsiDocComment doc = field.getDocComment();
        if (doc != null) {
            StringBuilder desc = new StringBuilder();
            for (PsiElement el : doc.getDescriptionElements()) {
                desc.append(el.getText().trim()).append(" ");
            }
            return desc.toString().trim();
        }
        return "";
    }

    private static String getCatalogName(PsiClass psiClass) {
        PsiDocComment docComment = psiClass.getDocComment();
        String catalogName = "";

        if (docComment != null) {
            PsiElement[] desc = docComment.getDescriptionElements();
            StringBuilder sb = new StringBuilder();
            for (PsiElement el : desc) {
                sb.append(el.getText());
            }
            String fullDesc = sb.toString().trim();
            if (!fullDesc.isEmpty()) {
                String[] lines = fullDesc.split("\\r?\\n");
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        catalogName = line.trim();
                        break;
                    }
                }
            }
        }

        if (catalogName.isEmpty()) {
            catalogName = psiClass.getName();
        }

        if (catalogName != null) {
            catalogName = catalogName.replace("-", "/");
        }

        return catalogName != null ? catalogName : "";
    }

    private static boolean hasAnnotation(PsiModifierListOwner element, String annotationFqn) {
        return element.hasAnnotation(annotationFqn);
    }

    private static String getWebAnnotation(PsiMethod method) {
        String[] annotations = {
                "org.springframework.web.bind.annotation.RequestMapping",
                "org.springframework.web.bind.annotation.GetMapping",
                "org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.PutMapping",
                "org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.PatchMapping"
        };
        for (String ann : annotations) {
            if (hasAnnotation(method, ann)) {
                return ann;
            }
        }
        return null;
    }

    private static String getHttpMethod(String annotationFqn, PsiMethod method) {
        if (annotationFqn.contains("GetMapping")) return "get";
        if (annotationFqn.contains("PostMapping")) return "post";
        if (annotationFqn.contains("PutMapping")) return "put";
        if (annotationFqn.contains("DeleteMapping")) return "delete";
        if (annotationFqn.contains("PatchMapping")) return "patch";

        if (annotationFqn.contains("RequestMapping")) {
            PsiAnnotation ann = method.getAnnotation(annotationFqn);
            if (ann != null) {
                PsiAnnotationMemberValue methodVal = ann.findAttributeValue("method");
                if (methodVal != null) {
                    String text = methodVal.getText().toLowerCase();
                    if (text.contains("get")) return "get";
                    if (text.contains("post")) return "post";
                    if (text.contains("put")) return "put";
                    if (text.contains("delete")) return "delete";
                }
            }
        }
        return "get";
    }

    private static String getMappingPath(PsiModifierListOwner owner, String annotationFqn) {
        PsiAnnotation ann = owner.getAnnotation(annotationFqn);
        if (ann != null) {
            PsiAnnotationMemberValue val = ann.findAttributeValue("value");
            if (val == null) {
                val = ann.findAttributeValue("path");
            }
            if (val != null) {
                String text = val.getText();
                if (text.startsWith("\"") && text.endsWith("\"")) {
                    text = text.substring(1, text.length() - 1);
                } else if (text.startsWith("{") && text.endsWith("}")) {
                    int firstQuote = text.indexOf("\"");
                    int secondQuote = text.indexOf("\"", firstQuote + 1);
                    if (firstQuote != -1 && secondQuote != -1) {
                        text = text.substring(firstQuote + 1, secondQuote);
                    }
                }
                return text;
            }
        }
        return "";
    }

    private static DocPageInfo generateEnumDocPage(PsiClass psiClass) {
        PsiDocComment docComment = psiClass.getDocComment();
        String title = psiClass.getName();
        String description = "";

        if (docComment != null) {
            PsiElement[] descriptionElements = docComment.getDescriptionElements();
            StringBuilder descBuilder = new StringBuilder();
            for (PsiElement el : descriptionElements) {
                descBuilder.append(el.getText());
            }

            String fullDesc = descBuilder.toString().trim();
            if (!fullDesc.isEmpty()) {
                String[] lines = fullDesc.split("\\r?\\n");
                boolean foundTitle = false;
                StringBuilder remainingDesc = new StringBuilder();

                for (String line : lines) {
                    String trimmedLine = line.trim();
                    if (!foundTitle) {
                        if (trimmedLine.isEmpty()) continue;
                        title = trimmedLine;
                        foundTitle = true;
                    } else {
                        remainingDesc.append(trimmedLine).append("\n");
                    }
                }

                if (remainingDesc.length() > 0) {
                    description = remainingDesc.toString().trim();
                } else {
                    description = title;
                }
            } else {
                description = title;
            }
        } else {
            description = title;
        }

        List<PsiEnumConstant> constants = new ArrayList<>();
        for (PsiField field : psiClass.getFields()) {
            if (field instanceof PsiEnumConstant) {
                constants.add((PsiEnumConstant) field);
            }
        }

        if (constants.isEmpty()) {
            return null;
        }

        boolean hasArgs = false;
        for (PsiEnumConstant constant : constants) {
            if (constant.getArgumentList() != null && constant.getArgumentList().getExpressions().length > 0) {
                hasArgs = true;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**简要描述：**\n\n");
        if (description.startsWith("-") || description.startsWith("*") || description.contains("\n")) {
            sb.append(description).append("\n\n");
        } else {
            sb.append("- ").append(description).append("\n\n");
        }

        sb.append("**枚举值列表：**\n\n");
        if (hasArgs) {
            sb.append("| 枚举项 (Constant) | 构造参数 (Arguments) | 描述 (Description) |\n");
            sb.append("|:---|:---|:---|\n");
            for (PsiEnumConstant constant : constants) {
                String name = constant.getName();
                
                List<String> argStrings = new ArrayList<>();
                if (constant.getArgumentList() != null) {
                    for (PsiExpression expr : constant.getArgumentList().getExpressions()) {
                        argStrings.add("`" + expr.getText() + "`");
                    }
                }
                String argsText = String.join(", ", argStrings);
                if (argsText.isEmpty()) argsText = "-";

                String descText = getEnumConstantDescription(constant);
                if (descText.isEmpty()) descText = "-";

                sb.append(String.format("|%s|%s|%s|\n", name, argsText, descText));
            }
        } else {
            sb.append("| 枚举项 (Constant) | 描述 (Description) |\n");
            sb.append("|:---|:---|\n");
            for (PsiEnumConstant constant : constants) {
                String name = constant.getName();
                String descText = getEnumConstantDescription(constant);
                if (descText.isEmpty()) descText = "-";

                sb.append(String.format("|%s|%s|\n", name, descText));
            }
        }
        sb.append("\n");

        String catalog = getCatalogName(psiClass);
        return new DocPageInfo(catalog, title, sb.toString());
    }

    private static String getEnumConstantDescription(PsiEnumConstant constant) {
        PsiDocComment doc = constant.getDocComment();
        if (doc != null) {
            StringBuilder sb = new StringBuilder();
            for (PsiElement el : doc.getDescriptionElements()) {
                sb.append(el.getText().trim()).append(" ");
            }
            return sb.toString().trim();
        }

        // Check for preceding block or line comment
        PsiElement sibling = constant.getPrevSibling();
        while (sibling != null && !(sibling instanceof PsiComment) && !(sibling instanceof PsiEnumConstant)) {
            sibling = sibling.getPrevSibling();
        }
        if (sibling instanceof PsiComment) {
            String text = sibling.getText().trim();
            if (text.startsWith("//")) {
                return text.substring(2).trim();
            } else if (text.startsWith("/*")) {
                text = text.substring(2);
                if (text.endsWith("*/")) {
                    text = text.substring(0, text.length() - 2);
                }
                return text.trim();
            }
        }

        // Check for trailing comment on the same line
        PsiElement nextSibling = constant.getNextSibling();
        while (nextSibling != null && !(nextSibling instanceof PsiComment) && !(nextSibling instanceof PsiEnumConstant) && !nextSibling.getText().contains("\n")) {
            nextSibling = nextSibling.getNextSibling();
        }
        if (nextSibling instanceof PsiComment) {
            String text = nextSibling.getText().trim();
            if (text.startsWith("//")) {
                return text.substring(2).trim();
            } else if (text.startsWith("/*")) {
                text = text.substring(2);
                if (text.endsWith("*/")) {
                    text = text.substring(0, text.length() - 2);
                }
                return text.trim();
            }
        }

        return "";
    }
}
