package com.example.demo;

/**
 * Java helper class providing math utilities for the demo plugin.
 * Demonstrates that plugin packages can include Java source files
 * alongside Kotlin sources.
 */
public class MathHelper {

    /**
     * Calculate the factorial of a non-negative integer.
     * @param n A number between 0 and 20 (inclusive)
     * @return n!
     * @throws IllegalArgumentException if n is negative or greater than 20
     */
    public static long factorial(int n) {
        if (n < 0 || n > 20) {
            throw new IllegalArgumentException("n must be between 0 and 20, got: " + n);
        }
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    /**
     * Check if a number is prime.
     * @param n The number to check
     * @return true if n is a prime number, false otherwise
     */
    public static boolean isPrime(int n) {
        if (n < 2) return false;
        if (n == 2) return true;
        if (n % 2 == 0) return false;
        for (int i = 3; i * i <= n; i += 2) {
            if (n % i == 0) return false;
        }
        return true;
    }

    /**
     * Calculate the greatest common divisor of two numbers.
     * @param a First number
     * @param b Second number
     * @return GCD of a and b
     */
    public static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }
}
