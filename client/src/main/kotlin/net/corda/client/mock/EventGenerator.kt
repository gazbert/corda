package net.corda.client.mock

import net.corda.contracts.asset.Cash
import net.corda.core.contracts.*
import net.corda.core.crypto.Party
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.transactions.TransactionBuilder
import net.corda.flows.CashCommand

/**
 * [Generator]s for incoming/outgoing events to/from the [WalletMonitorService]. Internally it keeps track of owned
 * state/ref pairs, but it doesn't necessarily generate "correct" events!
 */
class EventGenerator(
        val parties: List<Party>,
        val notary: Party
) {

    private var vault = listOf<StateAndRef<Cash.State>>()

    val issuerGenerator =
            Generator.pickOne(parties).combine(Generator.intRange(0, 1)) { party, ref -> party.ref(ref.toByte()) }

    val currencies = setOf(USD, GBP, CHF).toList() // + Currency.getAvailableCurrencies().toList().subList(0, 3).toSet()).toList()
    val currencyGenerator = Generator.pickOne(currencies)

    val issuedGenerator = issuerGenerator.combine(currencyGenerator) { issuer, currency -> Issued(issuer, currency) }
    val amountIssuedGenerator = generateAmount(1, 10000, issuedGenerator)

    val publicKeyGenerator = Generator.pickOne(parties.map { it.owningKey })
    val partyGenerator = Generator.pickOne(parties)

    val cashStateGenerator = amountIssuedGenerator.combine(publicKeyGenerator) { amount, from ->
        val builder = TransactionBuilder(notary = notary)
        builder.addOutputState(Cash.State(amount, from))
        builder.addCommand(Command(Cash.Commands.Issue(), amount.token.issuer.party.owningKey))
        builder.toWireTransaction().outRef<Cash.State>(0)
    }

    val consumedGenerator: Generator<Set<StateRef>> = Generator.frequency(
            0.7 to Generator.pure(setOf()),
            0.3 to Generator.impure { vault }.bind { states ->
                Generator.sampleBernoulli(states, 0.2).map { someStates ->
                    val consumedSet = someStates.map { it.ref }.toSet()
                    vault = vault.filter { it.ref !in consumedSet }
                    consumedSet
                }
            }
    )
    val producedGenerator: Generator<Set<StateAndRef<ContractState>>> = Generator.frequency(
            //            0.1 to Generator.pure(setOf())
            0.9 to Generator.impure { vault }.bind { states ->
                Generator.replicate(2, cashStateGenerator).map {
                    vault = states + it
                    it.toSet()
                }
            }
    )

    val issueRefGenerator = Generator.intRange(0, 1).map { number -> OpaqueBytes(ByteArray(1, { number.toByte() })) }

    val amountGenerator = Generator.intRange(0, 10000).combine(currencyGenerator) { quantity, currency -> Amount(quantity.toLong(), currency) }

    val issueCashGenerator =
            amountGenerator.combine(partyGenerator, issueRefGenerator) { amount, to, issueRef ->
                CashCommand.IssueCash(
                        amount,
                        issueRef,
                        to,
                        notary
                )
            }

    val moveCashGenerator =
            amountIssuedGenerator.combine(
                    partyGenerator
            ) { amountIssued, recipient ->
                CashCommand.PayCash(
                        amount = amountIssued,
                        recipient = recipient
                )
            }

    val exitCashGenerator =
            amountIssuedGenerator.map {
                CashCommand.ExitCash(
                        it.withoutIssuer(),
                        it.token.issuer.reference
                )
            }

    val clientCommandGenerator = Generator.frequency(
            1.0 to moveCashGenerator
    )

    val bankOfCordaCommandGenerator = Generator.frequency(
            0.6 to issueCashGenerator,
            0.4 to exitCashGenerator
    )
}