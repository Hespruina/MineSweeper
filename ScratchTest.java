import java.lang.reflect.*;
import java.util.List;

public class ScratchTest {
    public static void main(String[] a) throws Exception {
        String expr = "pow(2, 10)";
        Class<?> ec = Class.forName("top.zhrhello.mineSweeper.logic.Expression");
        Class<?> tk = Class.forName("top.zhrhello.mineSweeper.logic.Expression$Tokenizer");
        Constructor<?> ctor = tk.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        Object tok = ctor.newInstance(expr);
        Method m = tk.getDeclaredMethod("tokenize");
        m.setAccessible(true);
        List<?> tokens = (List<?>) m.invoke(tok);
        for (Object t : tokens) {
            Field tf = t.getClass().getDeclaredField("type");
            Field tx = t.getClass().getDeclaredField("text");
            tf.setAccessible(true); tx.setAccessible(true);
            System.out.println(tf.get(t) + "  text='" + tx.get(t) + "'");
        }
        // now try evaluate
        Method ev = ec.getDeclaredMethod("evaluate", String.class, long.class);
        ev.setAccessible(true);
        Object r = ev.invoke(null, expr, System.nanoTime() + 5000000000L);
        System.out.println("RESULT=" + r);
    }
}
