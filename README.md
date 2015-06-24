# Gitter to Slack Relay

This standalone Spring Boot application will relay messages from a Gitter chat room to a Slack webhook. Its purpose is to unify the platforms so that if you have a Slack channel open all day, you can receive notifications of chat messages from a Gitter chat that you use for public support, without having to have the Gitter application or web page open in addition to Slack.

### Usage

To use it, you must have some CLI parameters set. You need `--gitter.token` and `--gitter.roomId` options and a `--slack.webhookUrl` option. The former should be set to your Gitter token that will be used to set the `Authorization: Bearer` header as well as the unique room ID of your chat room on Gitter and the latter will be used as the Slack webhook URL to which a formatted message will be `POST`ed when a Gitter message is received.

### Running

The Relay is a plain Spring Boot application. You run it just like any other. To generate the single, executable jar file:

    > ./gradlew bootRepackage

Then to run the app:

    > java -jar build/libs/gitter-slack-relay-0.0.1-SNAPSHOT.jar --gitter.token=GITTER_TOKEN --gitter.roomId=GITTER_ROOM --slack.webhookUrl=SLACK_WEBHOOK

