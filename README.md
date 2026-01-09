# Distributed Bitcoin Miner (RPC-based)

> **ECE 751: Distributed Computing | University of Waterloo**
> **🏆 Grade: 98% / 100 (High Distinction)**

![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=java)
![Thrift](https://img.shields.io/badge/Apache_Thrift-0.16+-231F20?style=flat-square)
![Build](https://img.shields.io/badge/Build-Passing-brightgreen?style=flat-square)

## 📖 Overview

This project implements a scalable, fault-tolerant **Bitcoin Mining Pool** system using a **Client-Server RPC architecture**. It was developed as a core assignment for the Distributed Computing course, achieving a **near-perfect score (98%)** due to its robust concurrency handling and efficient load balancing.

The system solves Proof-of-Work (PoW) puzzles by distributing the computational load across a dynamic cluster of nodes, featuring a Front-End (FE) dispatcher and horizontally scalable Back-End (BE) workers.

## 🏗 Architecture

The system follows a 3-tier distributed architecture:

1.  **Client Layer**:
    * Initiates mining requests (`mineBlock`) with block header parameters.
    * Supports real-time cancellation (`cancel`) to simulate competitive mining scenarios.
2.  **Front-End (FE) Server**:
    * Acts as the centralized load balancer.
    * Smartly distributes tasks between local threads and available remote BE nodes.
    * Manages dynamic node registration.
3.  **Back-End (BE) Server**:
    * Performs the parallelized SHA-256 double-hashing logic.
    * Designed for horizontal scaling to linearly increase cluster throughput.

## ✨ Key Features

*  High Scalability: Supports the dynamic addition of Back-End nodes at runtime. Throughput increases linearly with each added node.
*  RPC Communication: Built on **Apache Thrift** for efficient, strongly-typed cross-process communication.
*  Robustness & Fault Tolerance:
    * **Startup Independence**: FE and BE nodes can be started in *any* order. The system automatically handles connection establishment and retries.
    * **Concurrency Control**: Thread-safe implementation handling concurrent `mineBlock` and `cancel` requests without race conditions.
*  Bitcoin Protocol Compliance: Fully implements the Bitcoin block header structure (Version, Previous Hash, Merkle Root, Time, Target, Nonce).

## 🛠 Tech Stack

* **Language**: Java 21
* **Middleware**: Apache Thrift (RPC Framework)
* **Concurrency**: Java Thread Pool & Synchronization
* **Build Tool**: Bash / Shell Scripting

## 🚀 Getting Started

### Prerequisites
* Java JDK 21
* Apache Thrift Compiler

### Build
Compile the project using the provided build script:
```bash
./build.sh
