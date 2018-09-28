/*
 * Copyright DataStax, Inc.
 *
 * This software is subject to the below license agreement.
 * DataStax may make changes to the agreement from time to time,
 * and will post the amended terms at
 * https://www.datastax.com/terms/datastax-dse-bulk-utility-license-terms.
 */
package com.datastax.kafkaconnector.util;

/** Utility methods for manipulating strings. */
public class StringUtil {
  /** This is a utility class and should never be instantiated. */
  private StringUtil() {}

  public static String singleQuote(String s) {
    return "'" + s + "'";
  }

  public static boolean isEmpty(String s) {
    return s == null || s.isEmpty();
  }
}
