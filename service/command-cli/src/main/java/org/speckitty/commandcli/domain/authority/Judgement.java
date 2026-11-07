package org.speckitty.commandcli.domain.authority;

/** Result of judging a request. */
public sealed interface Judgement permits Judgement.Accepted, Judgement.Refused {
  record Accepted() implements Judgement {}

  record Refused(Refusal refusal) implements Judgement {}
}
