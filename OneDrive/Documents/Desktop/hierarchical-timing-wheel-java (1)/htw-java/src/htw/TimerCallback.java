package htw;

/** Action to run when a timer fires. Receives the id of the timer that fired. */
@FunctionalInterface
public interface TimerCallback {
    void fire(long id);
}
