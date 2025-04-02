package com.bigbasket;

import io.vertx.core.Vertx;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new PaymentService(), res -> {
            if (res.succeeded()) {
                System.out.println("Payment Service deployed successfully!");
            } else {
                System.err.println("Failed to deploy Payment Service: " + res.cause());
            }
        });
    }
}