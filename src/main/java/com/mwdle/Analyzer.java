package com.mwdle;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
     * Configures the Jira API URI and authentication.
     */
    private static void configureJiraApi() {
        RestAssured.baseURI = env.getProperty("jira.host");
        RestAssured.authentication = RestAssured.preemptive().basic(env.getProperty("jira.user"), env.getProperty("jira.password"));
    }

    /**
     * Exception class for Jira API errors
     */
    public static class JiraApiException extends Exception {
        public JiraApiException(String message) {
            super(message);
        }
    }

    /**
     * <a href="https://developer.atlassian.com/server/jira/platform/rest/v10002/intro/#structure">Jira REST API Documentation</a>
     * Expects you to call the configureJiraApi() method prior to calling this method.
     *
     * @param jiraCode The Jira issue code (e.g. JIRA-1234)
     * @return The Jira issue JSON object
     */
    private static Response loadJiraIssue(String jiraCode) throws JiraApiException {
        try {
            return RestAssured.given().get("/rest/api/2/issue/" + jiraCode)
                    .then()
                    .statusCode(200).extract().response();
        }
        catch (Exception e) {
            throw new JiraApiException("Error loading Jira issue: " + jiraCode + "\nIs the Jira instance running?\nAre you connected to the VPN (if applicable)?\n");
        }
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
               Analyze the following Jira issue and report whether it contains adequate user acceptance criteria to allow members involved in the Agile pipeline to understand what is necessary to validate the changes on the issue. The idea is to help us decide whether we need to ask the issue reporters to provide more information if necessary.
                 Respond in the following JSON format:
               
                 {"contains_uac": <boolean>, "should_manually_review": <boolean>}
               
                 Guidelines:
                 - If the issue clearly describes, implies, outlines, or references any sort of context, information, instructions, or attachments that seem to provide an idea of what is necessary, respond with {"contains_uac": true}
                 - If the issue details are lacking in information and/or attachments and likely don't contain sufficient specific criteria for our QA team, respond with {"contains_uac": false}.
                 - If you are unsure or unable to determine whether the issue contains user acceptance criteria and are confident that further review is warranted, respond with {"contains_uac": <boolean>, "should_manually_review": true}, otherwise respond with {"contains_uac": <boolean>, "should_manually_review": false}. Use this option SPARINGLY.
               
                 Jira issue:
                 Title: %s
                 Description: %s
                 Attachments: %s
                 Issue Type: %s
                 Status: %s
                 Comments: %s
               """, title, description, attachments, issueType, status, comments);
    }

    /**
     * Creates the expected JSON response format for the LLM.
     * @return The expected JSON response format
     */
    private static JSONObject expectedResponseFormat() {
        JSONObject shouldManuallyReview = new JSONObject();
        shouldManuallyReview.put("type", "boolean");
        JSONObject containsUac = new JSONObject();
        containsUac.put("type", "boolean");

        JSONObject properties = new JSONObject();
        properties.put("contains_uac", containsUac);
        properties.put("should_manually_review", shouldManuallyReview);

        JSONObject format = new JSONObject();
        format.put("type", "object");
        format.put("properties", properties);
        return format;
    }

    /**
     * Pulls the selected model on the ollama instance
     * Prints the model pull progress
     */
    private static void pullModel(String model) {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("stream", true);
        System.out.print("Pulling the selected model: " + model);
        Response response = RestAssured.given().baseUri(env.getProperty("ollama.host", "http://localhost:11434")).header("Content-Type", "application/json").body(body.toString()).post("/api/pull");
        // Print the model pull progress
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody().asInputStream()))) {
            String line;
            String currentDigest = "";
            while ((line = reader.readLine()) != null) {
                JSONObject progress = new JSONObject(line);
                if (progress.has("total") && progress.has("completed")) {
                    // Print a new progress bar line anytime the resource being pulled changes
                    if (!progress.getString("digest").equals(currentDigest)) {
                        currentDigest = progress.getString("digest");
                        System.out.print("\n");
                    }
                    long completedInMB = progress.getLong("completed") / (1024 * 1024);
                    long totalInMB = progress.getLong("total") / (1024 * 1024);
                    double percentage = (completedInMB * 100.0) / totalInMB;
                    System.out.printf("\r%s (%.2f%%) [%d MB/%d MB]", progress.getString("status"), percentage, completedInMB, totalInMB);
                    System.out.flush();
                }
            }
            System.out.print("\n\n");
        } catch (IOException e) {
            System.out.println("Failed to pull the model: " + model);
            System.out.println("Error: " + e.getMessage());
            System.out.println("Is the ollama instance running?");
            System.exit(1);
        }
    }

    /**
     * Queries the ollama instance with the provided prompt and returns the response
     * @param prompt The prompt to send to the LLM
     * @return The response from the LLM in the format defined in the expectedResponseFormat() method.
     */
    private static String queryLLM(String prompt) {
        // Send the prompt to the LLM
        System.out.println("Querying the LLM...");
        JSONObject body = new JSONObject()
                .put("model", env.getProperty("ollama.model", "mistral"))
                .put("stream", false)
                .put("prompt", prompt)
                .put("format", expectedResponseFormat());
        Response response = RestAssured.given().baseUri(env.getProperty("ollama.host", "http://localhost:11434")).header("Content-Type", "application/json").body(body.toString()).post("/api/generate");
        if (response.jsonPath().getString("response") == null)
            return response.getBody().asString();
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
        pullModel(env.getProperty("ollama.model", "mistral"));
        boolean running = true;
        while (running) {
            System.out.print("Enter Jira issue code (or type 'exit' to quit): ");
            java.util.Scanner scanner = new java.util.Scanner(System.in);
            String jiraCode = scanner.nextLine();
            if (jiraCode.equals("exit")) {
                running = false;
                continue;
            }
            while (!jiraCode.matches("^[a-zA-Z]+-\\d+$")) {
                System.out.println("\nInvalid Jira issue code: '" + jiraCode + "'\n");
                System.out.print("Enter Jira issue code: ");
                jiraCode = scanner.nextLine();
            }
            configureJiraApi();
            Response jiraIssue;
            try {
                jiraIssue = loadJiraIssue(jiraCode);
            }
            catch (JiraApiException e) {
                System.out.println(e.getMessage());
                continue;
            }
            String prompt = generatePrompt(jiraIssue.jsonPath());
            RestAssured.reset(); // Reset the authentication so the Jira authentication headers are not sent with requests to the ollama instance.
            String llmResponse = queryLLM(prompt);
            System.out.println("Result: " + llmResponse);
            System.out.print("\n");
        }
    }
}