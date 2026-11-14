# Voyage-Server in one container: MySQL and the game server, with its admin page. The game data is not in the image:
# the assets folder is mounted when it runs (docker-compose.yml).

# The game server's jar.
FROM maven:3.9.6-amazoncorretto-21 AS jar
WORKDIR /opt/voyage-server
COPY pom.xml ./pom.xml
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -f ./pom.xml clean package -Dmaven.test.skip -T 1C

# The container: MySQL's own image, with Java added.
FROM mysql:8.4.0
COPY --from=jar /usr/lib/jvm/java-21-amazon-corretto /opt/java
ENV JAVA_HOME=/opt/java PATH=/opt/java/bin:$PATH
WORKDIR /opt/server
COPY --from=jar /opt/voyage-server/target/voyage-server.jar ./voyage-server.jar
# Scripts and settings are read when the server starts; docker-compose.yml mounts this folder's own over them.
COPY scripts ./scripts/
COPY config.yaml ./
COPY docker/start.sh /opt/start.sh
RUN chmod 755 /opt/start.sh
# The login server, world 1's channels (WWCC: WW is 75 plus the world number, CC 75 plus the channel number, both
# from 0), the admin page and the database.
EXPOSE 8484 7575 7576 7577 8585 3306
ENTRYPOINT ["/opt/start.sh"]
CMD []
