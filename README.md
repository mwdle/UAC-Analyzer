# User Acceptance Criteria Analyzer For Jira Issues

This is a proof-of-concept project to demonstrate how large language models can be used to analyze Jira ticket contents, and report on whether they contain sufficient user acceptance criteria for members involved in the Agile pipeline to determine what is necessary to validate the changes on the ticket.  

## Table of Contents

* [Description](#user-acceptance-criteria-analyzer-for-jira-issues)
* [Getting Started](#getting-started)
* [License](#license)
* [Disclaimer](#disclaimer)

## Getting Started

1. This program *requires* a GPU and is configured to use NVIDIA GPUs by default.
   If you want to use an AMD GPU, you must modify the ollama service in `compose.yaml` to use: `image: ollama/ollama:rocm`. Additionally, you must remove the `deploy:` section for NVIDIA GPUs from the ollama service configuration, and add the following to allow AMD GPU access in the container:

   ```yaml
   devices:
      - /dev/kfd
      - /dev/dri
    ```

2. Set up a file named environment.properties under `src/main/resources` containing the following properties:

    ```properties
   # Jira properties
   jira.user=<YOUR_USERNAME>
   jira.password=<YOUR_PASSWORD>
   jira.host=<YOUR_JIRA_URL>
   
   # Ollama properties
   # When running with the provided Docker Compose configuration, the ollama container is accessible to the analyzer in the Docker network under the following URL: http://ollama:11434
   # This should be changed appropriately only if you are running the analyzer outside the Docker Compose setup.
   ollama.host=http://ollama:11434
   # Enter the name of the model you want to use. A list of available models can be found at https://ollama.com/search (Only models marked as 'tools' are supported).
   # The default is a Google provided model called gemma 2, which has 27B parameters and requires at least 16GB of memory. In my experience it performs quite well for this task compared to other models.
   ollama.model=gemma2:27b
    ```

3. Run the following command (from the root of the repository) to build and start the containers:

    ```bash
    docker compose up -d
    ```

   > You can safely ignore the `pull access denied for mwdle/uac-analyzer` error, as the image is not on Docker Hub and is automatically built locally.

4. Now that the containers are running, to analyze a Jira issue, run the following commands:

    ```bash
    docker exec -it uac-analyzer java -jar /opt/uac-analyzer/app.jar
    ```

    You will now be prompted to enter the Jira issue code, and the LLM will analyze the issue and provide a response.

# License

This project is licensed under the MIT License. See the [LICENSE](LICENSE.txt) file for details.

## Disclaimer

This repository is provided as-is and is intended for informational and reference purposes only. The author assumes no responsibility for any errors or omissions in the content or for any consequences that may arise from the use of the information provided. Always exercise caution and seek professional advice if necessary.  
