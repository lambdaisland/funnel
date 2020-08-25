package lambdaisland.funnel;

import java.io.*;
import java.io.IOException;

public class Daemon {
  public static native int daemonize();

  static {
      System.loadLibrary("Daemon");
  }
}
