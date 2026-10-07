# Runs the orchestrator and all three scenarios in a clean container (JDK 21 + Maven).
#   docker build -t agentflow .
#   docker run --rm agentflow                          # full non-interactive demo
#   docker run --rm -it agentflow run greenfield       # interactive run (prompts for approvals)
FROM maven:3.9-eclipse-temurin-21

WORKDIR /app
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

COPY agentflow ./
COPY playbooks ./playbooks
COPY fixtures ./fixtures
COPY scenarios ./scenarios
COPY scripts ./scripts
# Warm the Maven repository with everything the generated projects need, so runs build offline.
RUN ./scripts/prefetch.sh

RUN useradd --create-home agent && chown -R agent /app && cp -r /root/.m2 /home/agent/.m2 && chown -R agent /home/agent/.m2
USER agent
ENTRYPOINT ["./agentflow"]
CMD ["demo"]
