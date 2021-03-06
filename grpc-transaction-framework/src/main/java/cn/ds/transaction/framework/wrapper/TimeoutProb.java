

package cn.ds.transaction.framework.wrapper;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Define timeout probe
 */
public class TimeoutProb implements Comparable<TimeoutProb> {
  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final transient Thread thread = Thread.currentThread();
  private final transient long startTime = System.currentTimeMillis();
  private final transient long expireTime;
  private Exception interruptFailureException = null;
  private boolean interruptSent = false;
  public TimeoutProb(int timeout) {
    this.expireTime = this.startTime + TimeUnit.SECONDS.toMillis(timeout);
  }

  @Override
  public int compareTo(final TimeoutProb obj) {
    int compare;
    if (this.expireTime > obj.expireTime) {
      compare = 1;
    } else if (this.expireTime < obj.expireTime) {
      compare = -1;
    } else {
      compare = 0;
    }
    return compare;
  }

  public Exception getInterruptFailureException() {
    return interruptFailureException;
  }

  /**
   * @return Returns TRUE if expired
   */
  public boolean expired() {
    return this.expireTime < System.currentTimeMillis();
  }

  /**
   * Interrupt thread
   *
   * @return Returns TRUE if the thread has been interrupted
   */
  public boolean interrupted() {
    boolean interrupted;
    if (this.thread.isAlive()) {
      // 如果当前线程是活动状态，则发送线程中断信号
      try {
        this.thread.interrupt();
        if(!interruptSent){
          LOG.warn("Thread interrupted on {}ms timeout (over {}ms)",
              new Object[]{System.currentTimeMillis() - this.startTime,
                  this.expireTime - this.startTime}
          );
        }
        interruptSent = true;
      } catch (Exception e) {
        this.interruptFailureException = e;
        LOG.info("Failed to interrupt the thread " + this.thread.getName(), e);
        throw e;
      }
      interrupted = false;
    } else {
      interrupted = true;
    }
    return interrupted;
  }
}
