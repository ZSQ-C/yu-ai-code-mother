import java.util.concurrent.CountDownLatch;

/**
 * 实验 2：共享堆对象 → 竞态（线程不安全）
 *
 * 运行方式：
 *   java e:\AI\GitHub\yu-ai-code-mother\experiments\Exp2_RaceCondition.java
 *
 * 观察目标：
 *   两个线程拿到的是【同一个堆对象】（同一个地址），
 *   同时执行 value++（读-改-写三步，不是原子操作），
 *   结果会【少于】期望值 —— 亲眼看到"共享"的代价。
 */
public class Exp2_RaceCondition {

    /** 被两个线程共享的对象：它住在堆里，只有一份 */
    static class Box {
        long value = 0;

        /** 注意：value++ 其实是三步：读 value → 加 1 → 写回 value，中间可能被别的线程插一脚 */
        void inc() {
            value++;
        }
    }

    /** 每个线程自增的次数 */
    static final int TIMES = 1_000_000;

    public static void main(String[] args) throws Exception {
        Box shared = new Box();   // ★ 堆里只 new 了 1 个对象

        // 用 CountDownLatch 让两个线程尽量"同时"起跑，方便复现竞态
        CountDownLatch startGate = new CountDownLatch(1);

        Runnable task = () -> {
            try {
                startGate.await();          // 等待发令枪
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < TIMES; i++) {
                shared.inc();
            }
        };

        Thread t1 = new Thread(task, "线程-1");
        Thread t2 = new Thread(task, "线程-2");

        System.out.println("========== 实验 2：共享对象的竞态 ==========");
        System.out.println("Box 对象所在的堆地址 = " + System.identityHashCode(shared));
        System.out.println("线程-1 和 线程-2 拿到的都是【这一个地址】→ 共享同一个对象");
        System.out.println();

        t1.start();
        t2.start();
        startGate.countDown();   // 发令枪响，两个线程同时开始

        t1.join();
        t2.join();

        long expected = 2L * TIMES;
        System.out.println("期望结果 = " + expected);
        System.out.println("实际结果 = " + shared.value);
        System.out.println("丢失了   = " + (expected - shared.value));
        System.out.println();
        System.out.println("---------- 结论 ----------");
        if (shared.value < expected) {
            System.out.println("★ 实际值小于期望值：两个线程确实在改【堆里同一个格子】，互相覆盖。");
        } else {
            System.out.println("本次刚好没丢（竞态不保证每次复现）。多跑几次，几乎一定会看到丢数。");
        }
        System.out.println("★ 记住：线程私有的只有【栈】；对象属于【堆】，谁拿到同一个引用谁就在共享。");
        System.out.println("★ 想看怎么修？跑 Exp5_FixRace.java。");
    }
}
