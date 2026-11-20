import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Drives the test IDE on the Xvfb display (test machine only): java tools/Ui.java click X Y | dblclick X Y |
 * ctrlclick X Y | drag X1 Y1 X2 Y2 | key NAME [NAME...] (e.g. key ctrl z) | type TEXT | move X Y.
 */
public class Ui {
    public static void main(String[] a) throws Exception {
        Robot r = new Robot();
        r.setAutoDelay(40);
        switch (a[0]) {
            case "move" -> r.mouseMove(i(a[1]), i(a[2]));
            case "click" -> { r.mouseMove(i(a[1]), i(a[2])); click(r, 1); }
            case "ctrlclick" -> { r.mouseMove(i(a[1]), i(a[2])); r.keyPress(KeyEvent.VK_CONTROL); click(r, 1); r.keyRelease(KeyEvent.VK_CONTROL); }
            case "dblclick" -> { r.mouseMove(i(a[1]), i(a[2])); click(r, 2); }
            case "drag" -> {
                int x1 = i(a[1]), y1 = i(a[2]), x2 = i(a[3]), y2 = i(a[4]);
                r.mouseMove(x1, y1); r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                for (int s = 1; s <= 20; s++) { r.mouseMove(x1 + (x2 - x1) * s / 20, y1 + (y2 - y1) * s / 20); Thread.sleep(30); }
                if (a.length > 5) Thread.sleep(Integer.parseInt(a[5]));
                r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            }
            case "hold" -> { // drag and stay pressed for a screenshot; released by "release"
                int x1 = i(a[1]), y1 = i(a[2]), x2 = i(a[3]), y2 = i(a[4]);
                r.mouseMove(x1, y1); r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                for (int s = 1; s <= 20; s++) { r.mouseMove(x1 + (x2 - x1) * s / 20, y1 + (y2 - y1) * s / 20); Thread.sleep(30); }
                Thread.sleep(Integer.parseInt(a[5]));
                r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            }
            case "key" -> {
                int[] codes = new int[a.length - 1];
                for (int k = 1; k < a.length; k++) codes[k - 1] = code(a[k]);
                for (int c : codes) r.keyPress(c);
                for (int k = codes.length - 1; k >= 0; k--) r.keyRelease(codes[k]);
            }
            case "type" -> {
                String shifted = "~!@#$%^&*()_+{}|:\"<>?", plain = "`1234567890-=[]\\;',./";
                for (char c : a[1].toCharArray()) {
                    int si = shifted.indexOf(c);
                    boolean up = Character.isUpperCase(c) || si >= 0;
                    int kc = KeyEvent.getExtendedKeyCodeForChar(si >= 0 ? plain.charAt(si) : c);
                    if (up) r.keyPress(KeyEvent.VK_SHIFT);
                    r.keyPress(kc); r.keyRelease(kc);
                    if (up) r.keyRelease(KeyEvent.VK_SHIFT);
                }
            }
        }
        Thread.sleep(100);
        System.exit(0);
    }
    static void click(Robot r, int n) throws Exception {
        for (int k = 0; k < n; k++) { r.mousePress(InputEvent.BUTTON1_DOWN_MASK); r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(60); }
    }
    static int i(String s) { return Integer.parseInt(s); }
    static int code(String n) throws Exception {
        return switch (n.toLowerCase()) {
            case "ctrl" -> KeyEvent.VK_CONTROL; case "shift" -> KeyEvent.VK_SHIFT; case "alt" -> KeyEvent.VK_ALT;
            case "enter" -> KeyEvent.VK_ENTER; case "esc" -> KeyEvent.VK_ESCAPE; case "delete" -> KeyEvent.VK_DELETE;
            case "tab" -> KeyEvent.VK_TAB; case "left" -> KeyEvent.VK_LEFT; case "right" -> KeyEvent.VK_RIGHT;
            case "up" -> KeyEvent.VK_UP; case "down" -> KeyEvent.VK_DOWN;
            default -> n.length() == 1 ? KeyEvent.getExtendedKeyCodeForChar(n.charAt(0)) : KeyEvent.class.getField("VK_" + n.toUpperCase()).getInt(null);
        };
    }
}
