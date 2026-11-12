package com.bookflow.catalog;

public record Device(long id, String code, String name, String category,
                     String location, String description, String status, boolean demo) {}
