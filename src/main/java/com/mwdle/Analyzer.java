package com.mwdle;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.io.InputStream;

public class Analyzer {

    private static final Properties env = new Properties();

    /**
     * Loads properties from the environment.properties file
     */
    public static void loadProperties() {
        try (InputStream input = Analyzer.class.getClassLoader().getResourceAsStream("environment.properties")) {
            if (input == null) {
                System.out.println("Property file not found! Exiting...");
                System.exit(1);
            }
            env.load(input);
        } catch (IOException e) {
            System.out.println("Error reading property file! Exiting...");
        }
    }

    /**
     * Authenticates with the Jira instance
     */
    private static void authenticate() {
        RestAssured.baseURI = env.getProperty("jira.host");
        RestAssured.authentication = RestAssured.preemptive().basic(env.getProperty("jira.user"), env.getProperty("jira.password"));
    }

    /**
     * <a href="https://developer.atlassian.com/server/jira/platform/rest/v10002/intro/#structure">Jira REST API Documentation</a>
     * @param jiraCode The Jira issue code (e.g. JIRA-1234)
     */
    private static Response loadJiraIssue(String jiraCode) {
        return RestAssured.given().get("/rest/api/2/issue/" + jiraCode)
                .then()
                .statusCode(200).extract().response();
    }

    /**
     * Generates a prompt to send to the LLM that instructs it to analyze the provided Jira issue details
     * @param title The title of the Jira issue
     * @param description The description of the Jira issue
     * @param attachments The number of attachments in the Jira issue
     * @param issueType The type of the Jira issue
     * @param status The status of the Jira issue
     * @param comments The comments on the Jira issue
     * @return The prompt to send to the LLM
     */
    private static String promptTemplate(String title, String description, int attachments, String issueType, String status, String comments) {
        return String.format("""
                Analyze the following Jira issue and report whether it contains some user acceptance criteria to allow our QA team to validate the changes on the issue. The idea is to help us decide whether we need to ask the issue reporters to provide more information if necessary. \s
                 Respond in JSON format: \s
               
                 { \s
                   "contains_uac": "Yes" or "No", \s
                   "should_manually_review": "Yes" or "No" \s
                 } \s
               
                 Guidelines: \s
                 - If the issue clearly describes, implies, outlines, or references any sort of context, information, instructions, or attachments that seem to help out enough with giving the QA an idea of what is necessary, respond with {"contains_uac": "Yes"} \s
                 - If the issue details are lacking in information and/or attachments, respond with {"contains_uac": "No"}. Use your judgment here -- the people involved in these issues generally have context already. \s
                 - If you are unable to determine whether the issue contains user acceptance criteria and are confident that further review is warranted, respond with {"contains_uac": "?", "should_manually_review": "Yes"}. Use this option SPARINGLY, as the main point of this is to avoid having to perform a manual review. \s
                 - Do NOT explain your answer under any circumstances. Respond only with RAW JSON. Do NOT wrap the JSON in any backticks or markdown. \s
               
                 Jira issue: \s
                 Title: %s \s
                 Description: %s \s
                 Attachments: %s \s
                 Issue Type: %s \s
                 Status: %s \s
                 Comments: %s
               """, title, description, attachments, issueType, status, comments);
    }

    /**
     * Queries the ollama instance with the provided prompt and returns the response
     * @param prompt The prompt to send to the LLM
     * @return The response from the LLM
     */
    private static String queryLLM(String prompt) {
        JSONObject body = new JSONObject();
        body.put("model", env.getProperty("ollama.model", "mistral"));
        body.put("prompt", prompt);
        body.put("stream", false);
        Response response = RestAssured.given().baseUri(env.getProperty("ollama.host", "http://localhost:11434")).header("Content-Type", "application/json").body(body.toString()).post("/api/generate");
        return response.jsonPath().getString("response");
    }

    /**
     * <a href="https://developer.atlassian.com/server/jira/platform/rest/v10002/api-group-issue/#api-agile-1-0-issue-issueidorkey-get">Jira REST API Documentation</a>
     * Serializes fields from the Jira issue into an LLM prompt that can be used to analyze it.
     * @param jiraIssue The Jira issue JSON object
     */
    private static String generatePrompt(JsonPath jiraIssue) {
        String title = jiraIssue.get("fields.summary");
        String description = jiraIssue.get("fields.description");
        int attachments = jiraIssue.get("fields.attachment.size()");
        String issueType = jiraIssue.get("fields.issuetype.name");
        String status = jiraIssue.get("fields.status.name");
        StringBuilder comments = new StringBuilder();
        List<Map<String, Object>> commentList = jiraIssue.getList("fields.comment.comments");
        for (Map<String, Object> comment : commentList) {
            comments.append(comment.get("created")).append(" - ").append(((Map<String, Object>)comment.get("author")).get("displayName")).append(": ").append(comment.get("body")).append(System.lineSeparator());
        }
        return promptTemplate(title, description, attachments, issueType, status, comments.toString());
    }

    public static void main(String[] args) {
        loadProperties();
        authenticate();
        System.out.print("Enter Jira issue code: ");
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        String jiraCode = scanner.nextLine();
        while (!jiraCode.matches("^[a-zA-Z]+-\\d+$")) {
            System.out.println("Invalid Jira issue code: '" + jiraCode + "'");
            System.out.print("Enter Jira issue code: ");
            jiraCode = scanner.nextLine();
        }
        Response jiraIssue = loadJiraIssue(jiraCode);
        String prompt = generatePrompt(jiraIssue.jsonPath());
        String llmResponse = queryLLM(prompt);
        System.out.print("\n");
        System.out.println("Result: " + llmResponse);
    }
}
