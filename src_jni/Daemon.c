#include <unistd.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <fcntl.h>
#include "jni.h"
#include "lambdaisland_funnel_Daemon.h"

JNIEXPORT jint JNICALL Java_lambdaisland_funnel_Daemon_daemonize(JNIEnv * env, jclass obj) {
  int pid = fork();

  if (pid == 0) { // succesfully forked
    umask(0);

    int sid = setsid(); // create a new session, this detaches us from our parent

    if (sid < 0) {
      exit(EXIT_FAILURE);
    }

    if ((chdir("/")) < 0) {
      exit(EXIT_FAILURE);
    }

    close(STDIN_FILENO);
    close(STDOUT_FILENO);
    close(STDERR_FILENO);

    open("/dev/null", O_RDWR);
    dup(0);
    dup(0);
  }

  return pid;
}
