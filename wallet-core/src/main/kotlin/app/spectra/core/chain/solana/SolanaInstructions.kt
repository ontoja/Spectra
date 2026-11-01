package app.spectra.core.chain.solana

object SystemInstructions {
    fun transfer(from: PublicKey, to: PublicKey, lamports: Long) = Instruction(
        SolanaPrograms.SYSTEM,
        listOf(AccountMeta(from, true, true), AccountMeta(to, false, true)),
        SolWriter().u32(2).u64(lamports).toByteArray(),
    )

    fun createAccountWithSeed(from: PublicKey, newAccount: PublicKey, base: PublicKey, seed: String, lamports: Long, space: Long, owner: PublicKey): Instruction {
        val seedBytes = seed.toByteArray()
        val data = SolWriter().u32(3).bytes(base.bytes).u64(seedBytes.size.toLong()).bytes(seedBytes)
            .u64(lamports).u64(space).bytes(owner.bytes).toByteArray()
        val metas = mutableListOf(AccountMeta(from, true, true), AccountMeta(newAccount, false, true))
        if (base != from) metas.add(AccountMeta(base, true, false))
        return Instruction(SolanaPrograms.SYSTEM, metas, data)
    }
}

object ComputeBudgetInstructions {
    fun setComputeUnitLimit(units: Int) = Instruction(SolanaPrograms.COMPUTE_BUDGET, emptyList(), SolWriter().u8(2).u32(units.toLong()).toByteArray())
    fun setComputeUnitPrice(microLamports: Long) = Instruction(SolanaPrograms.COMPUTE_BUDGET, emptyList(), SolWriter().u8(3).u64(microLamports).toByteArray())
}

object TokenInstructions {
    /** `TransferChecked` (index 12) — valid for both SPL Token and Token-2022 (decimals are verified on-chain). */
    fun transferChecked(tokenProgram: PublicKey, source: PublicKey, mint: PublicKey, destination: PublicKey, owner: PublicKey, amount: Long, decimals: Int): Instruction {
        require(SolanaPrograms.isTokenProgram(tokenProgram))
        return Instruction(
            tokenProgram,
            listOf(AccountMeta(source, false, true), AccountMeta(mint, false, false), AccountMeta(destination, false, true), AccountMeta(owner, true, false)),
            SolWriter().u8(12).u64(amount).u8(decimals).toByteArray(),
        )
    }

    /** Associated Token Account `CreateIdempotent` (instruction 1). */
    fun createAssociatedTokenAccountIdempotent(payer: PublicKey, ata: PublicKey, owner: PublicKey, mint: PublicKey, tokenProgram: PublicKey) = Instruction(
        SolanaPrograms.ASSOCIATED_TOKEN,
        listOf(
            AccountMeta(payer, true, true), AccountMeta(ata, false, true), AccountMeta(owner, false, false),
            AccountMeta(mint, false, false), AccountMeta(SolanaPrograms.SYSTEM, false, false), AccountMeta(tokenProgram, false, false),
        ),
        byteArrayOf(1),
    )
}

/** Native stake program instructions (bincode-encoded StakeInstruction enum). */
object StakeInstructions {
    const val STAKE_ACCOUNT_SPACE = 200L

    fun initialize(stake: PublicKey, staker: PublicKey, withdrawer: PublicKey) = Instruction(
        SolanaPrograms.STAKE,
        listOf(AccountMeta(stake, false, true), AccountMeta(SolanaPrograms.SYSVAR_RENT, false, false)),
        // Authorized { staker, withdrawer } + Lockup { unix_timestamp: 0, epoch: 0, custodian: default }
        SolWriter().u32(0).bytes(staker.bytes).bytes(withdrawer.bytes).i64(0).u64(0).bytes(ByteArray(32)).toByteArray(),
    )

    fun delegate(stake: PublicKey, vote: PublicKey, authority: PublicKey) = Instruction(
        SolanaPrograms.STAKE,
        listOf(
            AccountMeta(stake, false, true), AccountMeta(vote, false, false), AccountMeta(SolanaPrograms.SYSVAR_CLOCK, false, false),
            AccountMeta(SolanaPrograms.SYSVAR_STAKE_HISTORY, false, false), AccountMeta(SolanaPrograms.STAKE_CONFIG, false, false),
            AccountMeta(authority, true, false),
        ),
        SolWriter().u32(2).toByteArray(),
    )

    fun deactivate(stake: PublicKey, authority: PublicKey) = Instruction(
        SolanaPrograms.STAKE,
        listOf(AccountMeta(stake, false, true), AccountMeta(SolanaPrograms.SYSVAR_CLOCK, false, false), AccountMeta(authority, true, false)),
        SolWriter().u32(5).toByteArray(),
    )

    fun withdraw(stake: PublicKey, recipient: PublicKey, authority: PublicKey, lamports: Long) = Instruction(
        SolanaPrograms.STAKE,
        listOf(
            AccountMeta(stake, false, true), AccountMeta(recipient, false, true), AccountMeta(SolanaPrograms.SYSVAR_CLOCK, false, false),
            AccountMeta(SolanaPrograms.SYSVAR_STAKE_HISTORY, false, false), AccountMeta(authority, true, false),
        ),
        SolWriter().u32(4).u64(lamports).toByteArray(),
    )
}
