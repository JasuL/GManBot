package core;

import org.jnativehook.GlobalScreen;
import org.jnativehook.NativeHookException;
import org.jnativehook.keyboard.NativeKeyEvent;
import org.jnativehook.keyboard.NativeKeyListener;
import utility.GBUtility;

import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * Functionality - Button Press -> file output of: "date - Highlight - streamRecordingTimeStamp"
 */
public class BoblightTimeStamps implements NativeKeyListener {
    private static boolean activeStreamSession = false;
    private static LocalDateTime sessionStartTime = LocalDateTime.now();


    public static void main(String[] args) throws InterruptedException {
        BoblightTimeStamps boblights = new BoblightTimeStamps();

        try {
            GlobalScreen.registerNativeHook();
        } catch (NativeHookException e) {
            e.printStackTrace();
        }

        //remove logging
        LogManager.getLogManager().getLogger(GlobalScreen.class.getPackage().getName()).setLevel(Level.parse("OFF"));

        GlobalScreen.addNativeKeyListener(boblights);
    }


    /**
     * TODO: Implement this method to write "date - type - stream time: " to the highlight file
     * @param type
     */
    public static synchronized void createBoblightTimestamp(String type) {
        if (!activeStreamSession) return;
        GBUtility.writeTextToFile(type, "output/Stream Highlights.txt", true);
    }


    @Override
    public void nativeKeyPressed(NativeKeyEvent nativeKeyEvent) {
        if (nativeKeyEvent.getKeyCode() == 73) {

        }
        //System.out.println("Key Pressed: " + nativeKeyEvent.getKeyCode());
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent nativeKeyEvent) {

    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent nativeKeyEvent) {

    }
}
