package nl.cl.gram.cabalee;

public interface Stream<T> {
    void onNext(T t);
    void onComplete();
    void onError(Throwable t);
}
