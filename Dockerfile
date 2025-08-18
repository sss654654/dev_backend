FROM openjdk:17

COPY target/*.jar app.jar

EXPOSE 8080

CMD ["java", \
"-DDB=${DB}", "-DUSERNAME=${USERNAME}", "-DPASSWORD=${PASSWORD}", \
"-DWRITE_URL=${WRITE_URL}", "-DWRITE_PORT=${WRITE_PORT}", \
"-DREAD_URL=${READ_URL}", "-DREAD_PORT=${READ_PORT}", \
"-jar" ,"app.jar"]