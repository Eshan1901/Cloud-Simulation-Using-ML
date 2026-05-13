# Cloud Simulation using Machine Learning

## Overview
This project simulates a cloud datacenter environment utilizing the CloudSim Plus framework integrated with Machine Learning models. The primary objective is to evaluate and improve task scheduling and resource allocation under uncertainty. By employing machine learning to predict task execution behavior and potential SLA violations, the system optimizes datacenter efficiency, reduces waiting times, and minimizes energy consumption compared to traditional scheduling approaches.

## System Architecture and Framework
The system is built on the robust **CloudSim Plus** framework, extending its core capabilities to interface directly with external Machine Learning endpoints. 

### Key Components
- **Trace Loader:** Ingests and processes real-world Google Borg trace data (`borg_traces_data.csv`) to generate realistic cloudlets (tasks).
- **Datacenter and Hosts:** Simulates a physical datacenter with multiple hosts, tracking CPU, RAM utilization, and energy consumption dynamically.
- **Virtual Machines (VMs):** Handles execution environments for incoming tasks with Space-Shared scheduling.
- **ML Scheduler Broker:** Replaces standard heuristic schedulers. It communicates with an external Machine Learning inference API (served via FastAPI on Hugging Face) to retrieve predictions on task execution before making allocation decisions.

## Machine Learning-Driven Scheduling Strategy
The traditional scheduling algorithm assigns tasks without prior knowledge of their likely execution duration or probability of failure, often leading to unoptimized resource usage. This project solves this by using predictive models.

### Risk-Aware P90 Scheduler
The simulation includes an uncertainty-aware scheduler that utilizes quantile regression. Specifically, it requests the **P90 (90th percentile)** execution prediction from the machine learning model. This ensures that the scheduler provisions enough resources and sets appropriate priority levels even for tasks that have a high variance in their execution times, thereby acting as a risk-aware mechanism.

### Supported ML Models
The framework currently integrates with multiple trained models to predict task behavior, including:
- LightGBM (Default)
- Logistic Regression
- Random Forest
- XGBoost
