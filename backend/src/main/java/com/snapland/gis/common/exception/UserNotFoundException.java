package com.snapland.gis.common.exception;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String email) {
        super("No user found with email '" + email + "'.");
    }
}
