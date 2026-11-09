package edu.rit.swen755.inventory.protocol;

/** Every message on every port. {@link LineCodec} turns each into one line of text and back. */
public sealed interface Message permits Publish, Ack, Sale, SaleRecorded, ErrorReply, StatusRequest,
        StatusReply, SetFault, Cut, Restore, Ok {
}
