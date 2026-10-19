/**
 * 实验 1：栈 / 堆 / 方法区 —— 数据到底住在哪里？
 *
 * 运行方式（JDK 11+ 单文件源码启动，无需编译）：
 *   java e:\AI\GitHub\yu-ai-code-mother\experiments\Exp1_MemoryLayout.java
 *
 * 观察目标：
 *   1. new 两次 → 堆上出现两块独立空间（identityHashCode 不同）
 *   2. 实例字段各改各的 → 互不影响
 *   3. 静态字段全局只有一份 → 任何一个对象改了，别人读到的都是新值
 *   4. 栈里的变量只是"引用（地址）"，不是对象本体
 */
public class Exp1_MemoryLayout {

    /** 静态字段：属于【类】，类级别只有一份，概念上位于方法区（JDK 8+ 实现为元空间） */
    static int staticCounter = 0;

    /** 实例字段：属于【对象】，每个对象各有一份，住在堆里的对象内部 */
    int instanceValue = 0;

    public static void main(String[] args) {
        // ① new 两次 → 堆里划出两块独立空间
        Exp1_MemoryLayout a = new Exp1_MemoryLayout();
        Exp1_MemoryLayout b = new Exp1_MemoryLayout();

        // ② 两个对象各改自己的实例字段
        a.instanceValue = 100;
        b.instanceValue = 200;

        // ③ 改一次静态字段（通过类名访问，不需要对象）
        staticCounter = 999;

        // ④ 一个普通的局部变量 —— 它住在栈上
        int local = 12345;

        System.out.println("========== 实验 1：数据住在哪里 ==========");
        System.out.println();
        System.out.println("【栈】变量 a 只是一个引用，它指向的堆地址 = " + System.identityHashCode(a));
        System.out.println("【栈】变量 b 只是一个引用，它指向的堆地址 = " + System.identityHashCode(b));
        System.out.println("→ 两个地址不同，说明堆里有【两个独立对象】");
        System.out.println();
        System.out.println("【堆】a.instanceValue = " + a.instanceValue);
        System.out.println("【堆】b.instanceValue = " + b.instanceValue);
        System.out.println("→ 实例字段各改各的，互不影响：每个对象自己一份");
        System.out.println();
        System.out.println("a 读到的 staticCounter = " + a.staticCounter);
        System.out.println("b 读到的 staticCounter = " + b.staticCounter);
        System.out.println("Exp1_MemoryLayout.staticCounter = " + Exp1_MemoryLayout.staticCounter);
        System.out.println("→ 三处读到同一个值，说明静态字段【全局只有一份】，不属于任何一个对象");
        System.out.println();
        System.out.println("【栈】局部变量 local = " + local + "（方法结束后随栈帧销毁）");

        System.out.println();
        System.out.println("---------- 结论 ----------");
        System.out.println("栈：局部变量、方法参数、引用地址      → 线程私有，方法返回即销毁");
        System.out.println("堆：对象实例、实例字段、数组、String  → 所有线程共享，靠 GC 回收");
        System.out.println("方法区/元空间：类元信息、字节码、静态字段、常量池 → 所有线程共享，类级别一份");
        System.out.println();
        System.out.println("★ 关键：new 才是在堆里'创建空间'；栈里的变量只是指向那块空间的地址。");
    }
}
