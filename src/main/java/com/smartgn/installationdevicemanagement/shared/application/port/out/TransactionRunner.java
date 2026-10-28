package com.smartgn.installationdevicemanagement.shared.application.port.out;

import java.util.function.Supplier;

public interface TransactionRunner {
    <T> T run(Supplier<T> work);
}
