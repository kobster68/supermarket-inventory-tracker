/**
 * Small TCP helpers every process uses, so all four listen, connect, parse their command line and
 * report their ports the same way: bind {@code 127.0.0.1} with address reuse
 * ({@link edu.rit.swen755.inventory.protocol.net.Endpoints}), serve line connections
 * ({@link edu.rit.swen755.inventory.protocol.net.LineServer}), send one control request
 * ({@link edu.rit.swen755.inventory.protocol.net.ControlClient}), and start from {@code main}
 * ({@link edu.rit.swen755.inventory.protocol.net.Launcher}). The wire formats themselves live in
 * the parent package.
 */
package edu.rit.swen755.inventory.protocol.net;
