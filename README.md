# User Acceptance Criteria com.mwdle.Analyzer

This is a proof-of-concept project to demonstrate how an LLM can be used to analyze Jira ticket contents, and report on whether or not they contain sufficient user acceptance criteria for our QA team to determine whether the ticket can be resolved and moved along to user testing

## Getting Started

Run the following command (from the root of the repository) to start the container:

```bash
docker compose up -d
```

Once the container is running, execute the following command to start a server with an LLM of your choice, replacing `<LLM_NAME>` with the name of the LLM you want to use:

```bash
docker exec -it ollama ollama run <LLM_NAME>
```

The following example starts a `phi4` LLM instance:

```bash
docker exec -it ollama ollama run phi4
```
