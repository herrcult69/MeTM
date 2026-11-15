# MeTM
A Man in the middle attack simulation via Java Socket which is served to be a School Project of CE2 - Telecom Security

## Run the example

This example demonstrates Trudy intercepting a message sent by Alice and forwarding
it to Bob. It uses only the Java standard library.

### With `javac`

From the repository root, compile the three programs:

```sh
javac *.java
```

Open three terminals in the repository root and start the programs in this order:

```sh
java Bob
java Trudy
java Alice
```

Alice connects to Trudy on port `9000`; Trudy forwards the message to Bob on port
`9002` and relays Bob's response back to Alice.

### With Maven

Maven 3.10.0 or later can compile the same sources:

```sh
mvn compile
```

Run each class in its own terminal:

```sh
mvn exec:java -Dexec.mainClass=Bob
mvn exec:java -Dexec.mainClass=Trudy
mvn exec:java -Dexec.mainClass=Alice
```

The `exec:java` commands require the Maven Exec plugin to be available in the
local Maven repository. Alternatively, run the compiled classes directly:

```sh
java -cp target/classes Bob
java -cp target/classes Trudy
java -cp target/classes Alice
```
