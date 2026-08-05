package com.ae2vm.addon.vm;

/** Bytecode operations used by the iterative crafting interpreter. */
public enum Opcode {
    PUSH_ITEM(0x00),
    PUSH_LONG(0x01),
    ADD(0x02),
    SUB(0x03),
    MUL(0x04),
    DIV_ROUNDUP(0x05),
    EXTRACT_INGREDIENT(0x06),
    RECORD_OUTPUT(0x07),
    RECORD_INGREDIENT(0x08),
    RECORD_MISSING(0x09),
    DUP(0x0A),
    POP(0x0B),
    SWAP(0x0C),
    RECORD_PATTERN(0x0D),
    CALL(0x0E),
    RETURN(0x0F),
    CALL_BY_KEY(0x10),
    INSERT_OUTPUT(0x11),
    HALT(0xFF);

    public final int code;

    Opcode(int code) {
        this.code = code;
    }
}
