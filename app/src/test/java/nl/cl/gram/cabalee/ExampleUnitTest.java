package nl.cl.gram.cabalee;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void paddingSize() {
        assertEquals(ReceivingHandler.paddingSize(0), 127);
        for (int i = 0; i < 1000; i++) {
            assertTrue(ReceivingHandler.paddingSize(50) >= 127-50);
        }
    }
}