package nl.co.gram.cabalee;

import java.util.Date;

public class Message {
    public final Payload payload;
    public final Identity.PublicKey from;
    public final Date received;

    public Message(Payload payload, Identity.PublicKey from) {
        this.payload = payload;
        this.from = from;
        this.received = new Date();
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) return false;
        if (!getClass().equals(other.getClass())) return false;
        Message o = (Message) other;
        return this.payload.equals(o.payload) && this.from.identity().equals(o.from.identity()) && this.received.equals(o.received);
    }
}
