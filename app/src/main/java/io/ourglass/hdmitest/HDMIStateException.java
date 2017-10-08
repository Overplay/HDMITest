package io.ourglass.hdmitest;

/**
 * Created by mkahn on 10/8/17.
 *
 * Must extend a generic Exception for Android Studio to warn about no try/catch
 * IllegalStateException does not do this, even though it is better named.
 *
 */

public class HDMIStateException extends Exception {
    public HDMIStateException(String s) {
        super(s);
    }
}
