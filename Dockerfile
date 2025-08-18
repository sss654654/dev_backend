FROM openjdk:17

COPY target/*.jar app.jar

EXPOSE 8080

CMD ["java", \
"-DDB=${database}", "-DUSERNAME=${username}", "-DPASSWORD=${password}", \
"-DWRITE_URL=${writeUrl}", "-DWRITE_PORT=${writePort}", \
"-DREAD_URL=${readUrl}", "-DREAD_PORT=${readPort}", \
"-jar" ,"app.jar"]