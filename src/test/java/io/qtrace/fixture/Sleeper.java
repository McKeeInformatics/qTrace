package io.qtrace.fixture;

/** Test fixture: a separate process standing in for the running QuPath JVM. */
public class Sleeper {
    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(120_000);
    }
}
