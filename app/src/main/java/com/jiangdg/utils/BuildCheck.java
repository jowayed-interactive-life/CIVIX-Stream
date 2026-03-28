package com.jiangdg.utils;

import android.os.Build;

public final class BuildCheck {

  private BuildCheck() {
  }

  private static boolean check(final int value) {
    return Build.VERSION.SDK_INT >= value;
  }

  public static boolean isAndroid5() {
    return check(Build.VERSION_CODES.LOLLIPOP);
  }

  public static boolean isLollipop() {
    return check(Build.VERSION_CODES.LOLLIPOP);
  }

  public static boolean isMarshmallow() {
    return check(Build.VERSION_CODES.M);
  }
}
