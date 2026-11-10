package com.barnali.api.migration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

/** Holds only safe source-scan results; the function definition itself is never retained. */
record FunctionDefinitionSummary(
        String tableCreation,
        String tableAlteration,
        String rowLevelSecurity,
        String defaultPrivileges,
        boolean ddlCommandInspectionDetected,
        boolean createTableEventTagDetected,
        boolean schemaNameReferenceDetected,
        String schemaScope,
        boolean publicSchemaFilterDetected,
        boolean dynamicSqlDetected) {

    private static final Pattern TABLE_CREATION = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:UNLOGGED\\s+)?(?:TEMP(?:ORARY)?\\s+)?TABLE\\b");
    private static final Pattern TABLE_ALTERATION = Pattern.compile("(?is)\\bALTER\\s+TABLE\\b");
    private static final Pattern ROW_LEVEL_SECURITY = Pattern.compile(
            "(?is)\\b(?:ENABLE|DISABLE|FORCE|NO\\s+FORCE)\\s+ROW\\s+LEVEL\\s+SECURITY\\b");
    private static final Pattern DEFAULT_PRIVILEGES = Pattern.compile(
            "(?is)\\bALTER\\s+DEFAULT\\s+PRIVILEGES\\b");
    private static final Pattern DYNAMIC_SQL = Pattern.compile("(?is)\\bEXECUTE\\b");
    private static final Pattern PUBLIC_SCHEMA_FILTER = Pattern.compile(
            "(?is)\\b(?:schema_name|schema)\\s*=\\s*'public'");
    private static final Pattern ANY_SCHEMA_FILTER = Pattern.compile(
            "(?is)\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?schema_name\\s*(?:=|<>|!=|\\bIN\\b|\\bNOT\\s+IN\\b)");
    private static final Pattern SCHEMA_IS_NOT_NULL = Pattern.compile(
            "(?is)\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?schema_name\\s+IS\\s+NOT\\s+NULL\\b");
    private static final Pattern SCHEMA_IS_NULL = Pattern.compile(
            "(?is)\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?schema_name\\s+IS\\s+NULL\\b");
    private static final Pattern BARNALI_SCHEMA_FILTER = Pattern.compile(
            "(?is)\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?schema_name\\s*=\\s*'barnali'");
    private static final Pattern SCHEMA_FILTER = Pattern.compile(
            "(?is)\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?schema_name\\s*(NOT\\s+IN|IN|<>|!=|=)\\s*(\\([^)]{0,1024}\\)|'(?:''|[^'])*')");
    private static final Pattern SCHEMA_LITERAL = Pattern.compile("'((?:''|[^'])*)'");
    private static final Pattern DDL_COMMAND_INSPECTION = Pattern.compile(
            "(?is)\\bpg_event_trigger_ddl_commands\\s*\\(");
    private static final Pattern CREATE_TABLE_EVENT_TAG = Pattern.compile("(?is)'CREATE\\s+TABLE'");
    private static final Pattern SCHEMA_NAME_REFERENCE = Pattern.compile("(?is)\\bschema_name\\b");
    private static final Pattern SQL_LITERAL_OR_COMMENT = Pattern.compile(
            "(?is)'(?:''|[^'])*'|--[^\\r\\n]*|/\\*.*?\\*/");

    static FunctionDefinitionSummary analyze(String definition) {
        if (definition == null) {
            return new FunctionDefinitionSummary(
                    "unavailable", "unavailable", "unavailable", "unavailable",
                    false, false, false, "UNDETERMINED", false, false);
        }
        String body = functionBody(definition);
        boolean dynamicSql = DYNAMIC_SQL.matcher(body).find();
        String executableCode = SQL_LITERAL_OR_COMMENT.matcher(body).replaceAll(" ");
        boolean ddlInspection = DDL_COMMAND_INSPECTION.matcher(executableCode).find();
        boolean createTableTag = CREATE_TABLE_EVENT_TAG.matcher(body).find();
        boolean schemaNameReference = SCHEMA_NAME_REFERENCE.matcher(executableCode).find();
        boolean publicSchemaFilter = PUBLIC_SCHEMA_FILTER.matcher(body).find();
        return new FunctionDefinitionSummary(
                assessment(TABLE_CREATION, executableCode, body),
                assessment(TABLE_ALTERATION, executableCode, body),
                assessment(ROW_LEVEL_SECURITY, executableCode, body),
                assessment(DEFAULT_PRIVILEGES, executableCode, body),
                ddlInspection,
                createTableTag,
                schemaNameReference,
                schemaScope(body, ddlInspection, createTableTag),
                publicSchemaFilter,
                dynamicSql);
    }

    Boolean appliesToSchema(String schema) {
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            return null;
        }
        if (schemaScope.startsWith("ALL_NON_NULL_SCHEMA_NAME_EVENT_SCHEMAS")
                || schemaScope.startsWith("ALL_CREATE_TABLE_EVENT_SCHEMAS_NO_SCHEMA_NAME_FILTER_DETECTED")) {
            return true;
        }
        if ("PUBLIC_ONLY_FILTER_DETECTED".equals(schemaScope)) {
            return "public".equals(schema);
        }
        if ("BARNALI_ONLY_FILTER_DETECTED".equals(schemaScope)) {
            return "barnali".equals(schema);
        }
        if ("NULL_SCHEMA_NAME_COMMANDS_ONLY".equals(schemaScope)) {
            return false;
        }
        if (schemaScope.startsWith("ONLY_SCHEMA:")) {
            return schemaScope.substring("ONLY_SCHEMA:".length()).equals(schema);
        }
        if (schemaScope.startsWith("ALL_EXCEPT_SCHEMA:")) {
            return !schemaScope.substring("ALL_EXCEPT_SCHEMA:".length()).equals(schema);
        }
        if (schemaScope.startsWith("ONLY_SCHEMAS:")) {
            return List.of(schemaScope.substring("ONLY_SCHEMAS:".length()).split("\\|"))
                    .contains(schema);
        }
        if (schemaScope.startsWith("ALL_EXCEPT_SCHEMAS:")) {
            return !List.of(schemaScope.substring("ALL_EXCEPT_SCHEMAS:".length()).split("\\|"))
                    .contains(schema);
        }
        return null;
    }

    private static String schemaScope(String body, boolean ddlInspection, boolean createTableTag) {
        if (!ddlInspection || !createTableTag) {
            return "UNDETERMINED";
        }
        if (PUBLIC_SCHEMA_FILTER.matcher(body).find()) {
            return "PUBLIC_ONLY_FILTER_DETECTED";
        }
        if (BARNALI_SCHEMA_FILTER.matcher(body).find()) {
            return "BARNALI_ONLY_FILTER_DETECTED";
        }
        if (SCHEMA_IS_NOT_NULL.matcher(body).find()) {
            return "ALL_NON_NULL_SCHEMA_NAME_EVENT_SCHEMAS";
        }
        if (SCHEMA_IS_NULL.matcher(body).find()) {
            return "NULL_SCHEMA_NAME_COMMANDS_ONLY";
        }
        Matcher filter = SCHEMA_FILTER.matcher(body);
        if (filter.find()) {
            String operator = filter.group(1).replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
            Matcher literal = SCHEMA_LITERAL.matcher(filter.group(2));
            List<String> names = new java.util.ArrayList<>();
            while (literal.find()) {
                String name = literal.group(1).replace("''", "'");
                if (!name.matches("[a-z_][a-z0-9_]{0,62}")) {
                    return "SCHEMA_NAME_FILTER_PRESENT_UNCLASSIFIED";
                }
                names.add(name);
            }
            if (names.isEmpty()) {
                return "SCHEMA_NAME_FILTER_PRESENT_UNCLASSIFIED";
            }
            if ("=".equals(operator) && names.size() == 1) {
                return "ONLY_SCHEMA:" + names.get(0);
            }
            if (("<>".equals(operator) || "!=".equals(operator)) && names.size() == 1) {
                return "ALL_EXCEPT_SCHEMA:" + names.get(0);
            }
            if ("IN".equals(operator)) {
                return "ONLY_SCHEMAS:" + String.join("|", names);
            }
            if ("NOT IN".equals(operator)) {
                return "ALL_EXCEPT_SCHEMAS:" + String.join("|", names);
            }
            return "SCHEMA_NAME_FILTER_PRESENT_UNCLASSIFIED";
        }
        if (ANY_SCHEMA_FILTER.matcher(body).find()) {
            return "SCHEMA_NAME_FILTER_PRESENT_UNCLASSIFIED";
        }
        return "ALL_CREATE_TABLE_EVENT_SCHEMAS_NO_SCHEMA_NAME_FILTER_DETECTED";
    }

    private static String functionBody(String definition) {
        Matcher body = Pattern.compile("(?is)\\bAS\\s+\\$([A-Za-z0-9_]*)\\$(.*?)\\$\\1\\$")
                .matcher(definition);
        return body.find() ? body.group(2) : definition;
    }

    private static String assessment(Pattern effect, String executableCode, String body) {
        if (effect.matcher(executableCode).find()) {
            return "DETECTED_STATIC_SQL";
        }
        if (matchesAfterExecute(effect, body)) {
            return "DETECTED_DYNAMIC_SQL";
        }
        return "NOT_DETECTED";
    }

    private static boolean matchesAfterExecute(Pattern effect, String body) {
        Matcher execute = DYNAMIC_SQL.matcher(body);
        while (execute.find()) {
            int statementEnd = body.indexOf(';', execute.end());
            if (statementEnd < 0 || statementEnd - execute.end() > 4096) {
                statementEnd = Math.min(body.length(), execute.end() + 4096);
            }
            if (effect.matcher(body.substring(execute.end(), statementEnd)).find()) {
                return true;
            }
        }
        return false;
    }
}
