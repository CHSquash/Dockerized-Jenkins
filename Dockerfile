FROM alpine:3.3

ENV JENKINS_HOME /var/jenkins_home
ENV JENKINS_HOME_REF /usr/share/jenkins/ref/jenkins_home
ENV COPY_REFERENCE_FILE_LOG $JENKINS_HOME/copy_reference_file.log

ENV JENKINS_UC https://updates.jenkins-ci.org

EXPOSE 8080
EXPOSE 50000

RUN apk update \
  && apk add bash openjdk7 ttf-dejavu\
  && rm -rf \
    /tmp/* \
    /var/cache/apk/*

ADD https://github.com/krallin/tini/releases/download/v0.9.0/tini-static /bin/tini
RUN chmod +x /bin/tini

RUN mkdir -p $JENKINS_HOME_REF/init.groovy.d \
  && mkdir -p $JENKINS_HOME_REF/plugins \
  && mkdir -p $JENKINS_HOME \
  && touch $COPY_REFERENCE_FILE_LOG

# ADD http://mirrors.jenkins-ci.org/war/latest/jenkins.war /usr/share/jenkins/jenkins.war
COPY jenkins*.war /usr/share/jenkins/jenkins.war
COPY init.groovy.d $JENKINS_HOME_REF/init.groovy.d

CMD cp -r $JENKINS_HOME_REF /var \
  && /bin/tini -s -- java -jar /usr/share/jenkins/jenkins.war
