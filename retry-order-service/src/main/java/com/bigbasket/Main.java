package com.bigbasket;

import io.vertx.core.Vertx;

public class Main {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new RetryOrderService(), res -> {
            if (res.succeeded()) {
                System.out.println("Retry Order Service deployed successfully!");
            } else {
                System.err.println("Failed to deploy Retry Order Service: " + res.cause());
            }
        });
    }
}