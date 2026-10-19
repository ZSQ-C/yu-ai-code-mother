import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 实验 4：虚拟线程到底"轻"在哪？（JDK 21 正式特性）
 *
 * 运行方式（需要 JDK 21+，虚拟线程在 21 已正式，无需预览参数）：
 *   java e:\AI\GitHub\yu-ai-code-mother\experiments\Exp4_VirtualThreads.java
 *
 * 观察目标：
 *   每个任务只做一件事 —— "睡一会儿"（模拟等 LLM / 等浏览器 / 等数据库）。
 *   → 虚拟线程：几十毫秒级完成全部任务（因为等待时不占 OS 线程）
 *   → 固定线程池 200 个平台线程：需要【分批】等待，总耗时 ≈ 批数 × 睡眠时间
 *
 * 把 TASK_COUNT 改成 100_000 会更震撼（固定线程池版本会跑很久，可用 Ctrl+C 中断）。
 */
public class Exp4_VirtualThreads {

    /** 模拟的任务数量。改成 100_000 对比更夸张 */
    static final int TASK_COUNT = 5_000;

    /** 每个任务"等待 IO"的时长（毫秒） */
    static final long SLEEP_MS = 300;

    public static void main(String[] args) throws Exception {
        System.out.println("========== 实验 4：虚拟线程 vs 平台线程（都在等 IO）==========");
        System.out.println("任务数 = " + TASK_COUNT + "，每个任务只干一件事：Thread.sleep(" + SLEEP_MS + "ms)");
        System.out.println("理论上：所有任务同时睡 → 总耗时应接近 " + SLEEP_MS + "ms");
        System.out.println();

        // ---------- ① 虚拟线程：一个任务一个虚拟线程 ----------
        long vtStart = System.currentTimeMillis();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < TASK_COUNT; i++) {
                executor.submit(() -> {
                    sleepQuietly(SLEEP_MS);
                    return null;
                });
            }
        }   // try-with-resources 的 close() 会等待全部任务结束
        long vtCost = System.currentTimeMillis() - vtStart;
        System.out.println("① 虚拟线程（每个任务一个虚拟线程）  耗时 = " + vtCost + " ms");

        // ---------- ② 固定线程池：只有 200 个平台线程 ----------
        System.out.println();
        System.out.println("② 固定线程池（200 个平台线程）开始跑……（会比上面慢得多，请耐心等待）");
        long ptStart = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(200);
        List<Future<?>> futures = new ArrayList<>(TASK_COUNT);
        for (int i = 0; i < TASK_COUNT; i++) {
            futures.add(pool.submit(() -> {
                sleepQuietly(SLEEP_MS);
                return null;
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();   // 等每个任务完成
            } catch (Exception e) {
                // 本实验忽略异常
            }
        }
        pool.shutdown();
        long ptCost = System.currentTimeMillis() - ptStart;
        System.out.println("   固定线程池（200 个平台线程）      耗时 = " + ptCost + " ms");

        System.out.println();
        System.out.println("---------- 结果 ----------");
        System.out.println("虚拟线程耗时 = " + vtCost + " ms");
        System.out.println("平台线程耗时 = " + ptCost + " ms");
        System.out.println("倍数差距     = " + (vtCost == 0 ? "?" : String.format("%.1f", (double) ptCost / vtCost)) + " 倍");
        System.out.println();
        System.out.println("---------- 结论 ----------");
        System.out.println("★ 虚拟线程不是'跑得更快'，而是【等 IO 的时候不占 OS 线程】。");
        System.out.println("★ 平台线程只有 200 个 → 5000 个任务必须分 " + (TASK_COUNT / 200) + " 批等待；");
        System.out.println("  虚拟线程有 5000 个 → 大家一起等，一波就完事。");
        System.out.println("★ 机制：虚拟线程遇到阻塞 IO 时，JVM 把它 '卸载' 到堆上保存，");
        System.out.println("        载体线程（carrier）立刻去跑别的虚拟线程；IO 回来再挂载回来。");
        System.out.println();
        System.out.println("【注意】虚拟线程【不能】解决并发安全！");
        System.out.println("   共享的堆对象照样会互相覆盖 —— 把 Exp2 的线程换成虚拟线程，");
        System.out.println("   结果依然会丢数（Thread.ofVirtual().start(task) 即可）。");
        System.out.println();
        System.out.println("【注意】JDK 21 的已知瑕疵：虚拟线程在 synchronized 块里阻塞会被'钉住'(pin)，");
        System.out.println("   此时载体线程无法被复用，优势失效（JDK 24 的 JEP 491 已修复）。");
    }

    /** 让线程安静地睡一会儿，不抛受检异常 */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
