# MeTM
A Man in the middle attack simulation via Java Socket which is served to be a School Project of CE2 - Telecom Security

## Project layout

Standard Maven layout; sources live in `src/main/java/com/herrcult69/metm/`
(package `com.herrcult69.metm`).

## Build

Requires JDK 8+ and Maven 3.6+:

```sh
mvn clean package
```

## Run the example

Alice sends a message to Trudy (port `9000`), who forwards it to Bob (port `9002`)
and relays Bob's reply back. Open three terminals in the repository root and start
in this order:

```sh
java -cp target/classes com.herrcult69.metm.Bob
java -cp target/classes com.herrcult69.metm.Trudy
java -cp target/classes com.herrcult69.metm.Alice
```
