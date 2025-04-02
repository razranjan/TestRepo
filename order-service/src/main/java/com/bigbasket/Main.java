package com.bigbasket;

import io.vertx.core.Vertx;

public class Main {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new OrderService(), res -> {
            if (res.succeeded()) {
                System.out.println("Order Service deployed successfully!");
            } else {
                System.err.println("Failed to deploy Order Service: " + res.cause());
            }
        });
    }
}