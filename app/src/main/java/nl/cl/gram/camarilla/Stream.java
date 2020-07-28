package nl.cl.gram.camarilla;

public interface Stream<T> {
    void onNext(T t);
    void onComplete();
    void onError(Throwable t);
}
