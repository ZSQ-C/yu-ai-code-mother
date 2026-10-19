import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 实验 5：竞态怎么修？四种方案对比
 *
 * 运行方式：
 *   java e:\AI\GitHub\yu-ai-code-mother\experiments\Exp5_FixRace.java
 *
 * 观察目标：
 *   同一个"两个线程各自增 100 万次"的场景，用四种计数器实现，
 *   看哪一种会丢数、哪一种稳定正确。
 *
 * 结论预告：
 *   ① 什么都不做        → ❌ 丢数
 *   ② synchronized      → ✅ 正确（但 JDK 21 下会"钉住"虚拟线程，见 Exp4 末尾）
 *   ③ ReentrantLock     → ✅ 正确（虚拟线程场景优先选它）
 *   ④ AtomicLong        → ✅ 正确（CAS 无锁，并发量高时通常最快）
 */
public class Exp5_FixRace {

    static final int TIMES = 1_000_000;

    /** ① 裸的：读-改-写三步，非原子 → 会丢数 */
    static class BadCounter {
        long value = 0;

        void inc() {
            value++;
        }
    }

    /** ② synchronized：一次只允许一个线程进入 → 正确 */
    static class SyncCounter {
        long value = 0;

        synchronized void inc() {
            value++;
        }
    }

    /** ③ ReentrantLock：效果同 synchronized，但不会钉住虚拟线程 */
    static class LockCounter {
        final ReentrantLock lock = new ReentrantLock();
        long value = 0;

        void inc() {
            lock.lock();
            try {
                value++;
            } finally {
                lock.unlock();   // ★ 必须在 finally 里解锁，否则异常时死锁
            }
        }
    }

    /** ④ AtomicLong：底层 CAS 无锁原子操作，不阻塞、不上下文切换 */
    static class AtomicCounter {
        final AtomicLong value = new AtomicLong();

        void inc() {
            value.incrementAndGet();
        }

        long get() {
            return value.get();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========== 实验 5：修竞态的四种方案 ==========");
        System.out.println("场景：两个线程，各对同一个堆对象自增 " + TIMES + " 次");
        System.out.println("期望结果：" + (2L * TIMES));
        System.out.println();

        runCase("① 什么都不做（裸 value++）      ", new BadCounter());
        runCase("② synchronized                 ", new SyncCounter());
        runCase("③ ReentrantLock                ", new LockCounter());
        runCase("④ AtomicLong                   ", new AtomicCounter());

        System.out.println();
        System.out.println("---------- 结论 ----------");
        System.out.println("★ 修竞态的本质：把'读-改-写'变成【不可被打断的一个整体】。");
        System.out.println("   ② synchronized / ③ ReentrantLock → 互斥（同一时刻只有一个线程能进）");
        System.out.println("   ④ AtomicLong                     → 无锁 CAS（硬件指令保证原子）");
        System.out.println();
        System.out.println("★ 怎么选：");
        System.out.println("   简单计数 / 累加        → AtomicLong、LongAdder");
        System.out.println("   需要保护一段【代码块】 → ReentrantLock（JDK 21 配虚拟线程时优于 synchronized）");
        System.out.println("   只读、无并发的共享 Map  → ConcurrentHashMap");
        System.out.println();
        System.out.println("★ 对照本项目：");
        System.out.println("   单机场景  → ReentrantLock 就够（如给静态 webDriver 加锁串行化）");
        System.out.println("   多机场景  → 进程内锁失效，必须用 Redis 分布式锁（RLock / SETNX）；");
        System.out.println("              本项目里的 RRateLimiter 就是把'计数器'搬到了 Redis，所以多实例也准。");
    }

    /** 跑一个场景并打印结果 */
    private static void runCase(String label, Object counter) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);

        Runnable task = () -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < TIMES; i++) {
                incByType(counter);
            }
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);

        t1.start();
        t2.start();
        gate.countDown();
        t1.join();
        t2.join();

        long actual = valueOf(counter);
        long expected = 2L * TIMES;
        String flag = (actual == expected) ? "[正确]" : "[丢数 " + (expected - actual) + "]";

        System.out.println(label + " 实际 = " + actual + "   " + flag);
    }

    private static void incByType(Object counter) {
        if (counter instanceof BadCounter c) {
            c.inc();
        } else if (counter instanceof SyncCounter c) {
            c.inc();
        } else if (counter instanceof LockCounter c) {
            c.inc();
        } else if (counter instanceof AtomicCounter c) {
            c.inc();
        }
    }

    private static long valueOf(Object counter) {
        if (counter instanceof BadCounter c) {
            return c.value;
        } else if (counter instanceof SyncCounter c) {
            return c.value;
        } else if (counter instanceof LockCounter c) {
            return c.value;
        } else if (counter instanceof AtomicCounter c) {
            return c.get();
        }
        return -1;
    }
}
