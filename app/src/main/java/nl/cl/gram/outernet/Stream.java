package nl.cl.gram.outernet;

public interface Stream<T> {
    void onNext(T t);
    void onComplete();
    void onError(Throwable t);
}
