/**
 * The shared wire contract every process is built against. It is frozen: a change to a message
 * format or a STATUS key is its own small pull request.
 *
 * <h2>Transport</h2>
 * Plain TCP on {@code 127.0.0.1}. Every message is one UTF-8 line ending in {@code \n}; fields are
 * separated by {@code |}; the first field names the message type, except a STATUS reply, whose
 * fields are all {@code key=value}. {@link edu.rit.swen755.inventory.protocol.LineCodec} encodes and
 * decodes every message and rejects a malformed line with
 * {@link edu.rit.swen755.inventory.protocol.MalformedLineException}.
 *
 * <h2>Messages</h2>
 * <table>
 *   <caption>Every message, its line, and where it travels</caption>
 *   <tr><th>Line</th><th>From, to</th></tr>
 *   <tr><td>{@code PUBLISH|eventId|source|sequence|product|delta|timestamp}</td>
 *       <td>store to CFE, and unchanged CFE to master</td></tr>
 *   <tr><td>{@code ACK|accepted|source|through}</td><td>CFE to store</td></tr>
 *   <tr><td>{@code ACK|applied|source|through}</td><td>master to CFE</td></tr>
 *   <tr><td>{@code SALE|P1..P5|1-3}</td><td>point of sale to store sales port</td></tr>
 *   <tr><td>{@code OK|eventId|sequence} or {@code ERR|reason}</td><td>store reply to a sale</td></tr>
 *   <tr><td>{@code STATUS}</td><td>anyone to any control port</td></tr>
 *   <tr><td>{@code key=value|key=value|...}</td><td>reply to {@code STATUS}</td></tr>
 *   <tr><td>{@code SET|drop|<0-100>}, {@code SET|duplicate|<0-100>}, {@code SET|delay|<ms>},
 *       {@code CUT}, {@code RESTORE}</td><td>anyone to the fault link control port</td></tr>
 *   <tr><td>{@code OK} or {@code ERR|reason}</td><td>fault link reply to a control command</td></tr>
 * </table>
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li><b>Stock change.</b> {@code delta} is the signed change to stock, negative for a sale
 *       ({@code -quantity}). Both the store and the master add {@code delta} to the product's
 *       stock.</li>
 *   <li><b>Event identity.</b> The event ID is a UUID created with the sale. It never changes,
 *       including on every retry.</li>
 *   <li><b>Sequence.</b> Consecutive per source, starting at 1. Order, not timestamp, defines an
 *       event's position.</li>
 *   <li><b>Acknowledgements are cumulative.</b> {@code ACK|accepted|s|N} means every sequence of
 *       source {@code s} up to {@code N} is durably stored at the CFE; {@code ACK|applied|s|N}
 *       means every sequence up to {@code N} is applied at the master. A receiver acknowledges
 *       only after its transaction commits, and answers a duplicate or an event beyond a gap with
 *       its current position, so the sender resends from there.</li>
 *   <li><b>Timestamps</b> are ISO-8601 UTC ({@code 2026-11-09T18:30:00Z}) and informational
 *       only.</li>
 *   <li><b>STATUS.</b> The keys of each process are fixed in
 *       {@link edu.rit.swen755.inventory.protocol.StatusKeys}. Durable keys are computed from the
 *       process's SQLite tables on every request, so a crash cannot reset them; informational keys
 *       may be kept in memory and reset on restart.</li>
 *   <li><b>Connections.</b> A control connection carries one request line and one reply line,
 *       then closes. A sales connection may carry many sales, each answered in order. A sale that
 *       gets no reply is not retried.</li>
 *   <li><b>Initial stock.</b> Each of the five products starts at
 *       {@link edu.rit.swen755.inventory.protocol.Product#INITIAL_STOCK} at the store and in the
 *       master's copy for that store. Stock is seeded only when a data file is first created; a
 *       restarted process reopens its stock, Outbox, log and cursors and never re-seeds.</li>
 * </ul>
 *
 * <p>The retry policy both senders share is
 * {@link edu.rit.swen755.inventory.protocol.RetrySettings}; default ports are in
 * {@link edu.rit.swen755.inventory.protocol.Defaults}.
 */
package edu.rit.swen755.inventory.protocol;
