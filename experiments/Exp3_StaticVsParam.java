import java.util.concurrent.CountDownLatch;

/**
 * 实验 3：静态方法不背锅 —— 看它【碰了什么数据】
 *
 * 运行方式：
 *   java e:\AI\GitHub\yu-ai-code-mother\experiments\Exp3_StaticVsParam.java
 *
 * 观察目标（对照本项目 WorkflowContext.getContext() 与 WebScreenshotUtils）：
 *   ① 无状态静态方法（只操作入参 + 局部变量）→ 多线程调用永远安全
 *      （对应 WorkflowContext.getContext(state) / saveContext(context)）
 *   ② 有状态静态方法（访问静态字段）→ 多线程调用会出错
 *      （对应 WebScreenshotUtils 里操作静态字段 webDriver）
 */
public class Exp3_StaticVsParam {

    /** ① 无状态静态方法：只操作入参和自己栈上的局部变量，不碰任何静态字段 */
    static long addPure(long a, long b) {
        long sum = a + b;   // sum 是局部变量，住栈，每个线程一份
        return sum;
    }

    /** ② 有状态静态方法：访问静态字段 → 全局一份 → 多线程会互相覆盖 */
    static long staticTotal = 0;

    static void addStatic() {
        staticTotal++;
    }

    static final int TIMES = 1_000_000;

    public static void main(String[] args) throws Exception {
        System.out.println("========== 实验 3：静态方法 vs 静态字段 ==========");

        // ---------- ① 无状态静态方法：两个线程各调 TIMES 次 ----------
        // 每个线程把自己的结果写进 results 数组的【不同下标】，彼此不冲突
        long[] results = new long[2];
        CountDownLatch gate1 = new CountDownLatch(1);

        Thread p1 = new Thread(() -> {
            await(gate1);
            long s = 0;
            for (int i = 0; i < TIMES; i++) {
                s += addPure(i, 1);   // 只操作入参 → 安全
            }
            results[0] = s;
        });
        Thread p2 = new Thread(() -> {
            await(gate1);
            long s = 0;
            for (int i = 0; i < TIMES; i++) {
                s += addPure(i, 1);
            }
            results[1] = s;
        });

        p1.start();
        p2.start();
        gate1.countDown();
        p1.join();
        p2.join();

        long pureExpected = 2L * (TIMES * 1L) + 2L * (TIMES * (TIMES - 1L) / 2L);
        long pureActual = results[0] + results[1];

        System.out.println();
        System.out.println("① 无状态静态方法 addPure(a, b)：");
        System.out.println("   期望 = " + pureExpected);
        System.out.println("   实际 = " + pureActual);
        System.out.println("   → " + (pureExpected == pureActual
                ? "[正确] 静态方法只碰入参，多线程调用 100% 安全"
                : "[错误] 理论上不会出错，请检查"));

        // ---------- ② 有状态静态方法：访问静态字段 ----------
        CountDownLatch gate2 = new CountDownLatch(1);

        Runnable task = () -> {
            await(gate2);
            for (int i = 0; i < TIMES; i++) {
                addStatic();   // 访问静态字段 → 共享 → 不安全
            }
        };
        Thread s1 = new Thread(task, "线程-1");
        Thread s2 = new Thread(task, "线程-2");

        s1.start();
        s2.start();
        gate2.countDown();
        s1.join();
        s2.join();

        long staticExpected = 2L * TIMES;

        System.out.println();
        System.out.println("② 有状态静态方法 addStatic()（内部改静态字段 staticTotal）：");
        System.out.println("   期望 = " + staticExpected);
        System.out.println("   实际 = " + staticTotal);
        System.out.println("   丢失 = " + (staticExpected - staticTotal));
        System.out.println("   → " + (staticTotal < staticExpected
                ? "[错误] 静态字段全局一份，两个线程在改同一个格子"
                : "本次刚好没丢，多跑几次必然看到丢数"));

        System.out.println();
        System.out.println("---------- 结论 ----------");
        System.out.println("★ 静态方法本身不是问题，静态【字段】才是。");
        System.out.println("★ 判据一句话：静态方法里碰了非 final 的静态字段 → 有共享风险；");
        System.out.println("              只碰入参和局部变量（无状态）→ 天然线程安全。");
        System.out.println();
        System.out.println("★ 对照本项目：");
        System.out.println("   WorkflowContext.getContext(state)  只操作入参 → [安全]");
        System.out.println("   WebScreenshotUtils.saveWebPageScreenshot(url) 内部读静态字段 webDriver → [不安全]");
    }

    /** 等待发令枪的小工具 */
    private static void await(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
