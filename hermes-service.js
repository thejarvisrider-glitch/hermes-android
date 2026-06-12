/**
 * Hermes Gateway Windows Service Wrapper
 * 
 * Install:  node hermes-service.js install
 * Start:    node hermes-service.js start  
 * Stop:     node hermes-service.js stop
 * Remove:   node hermes-service.js remove
 */

const { exec, spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const HERMES_HOME = 'C:\\Users\\sande\\AppData\\Local\\hermes\\hermes-agent';
const PYTHON = path.join(HERMES_HOME, 'venv', 'Scripts', 'python.exe');
const RUN_AGENT = path.join(HERMES_HOME, 'run_agent.py');
const LOG_DIR = path.join(HERMES_HOME, 'logs');
const LOG_FILE = path.join(LOG_DIR, 'gateway-service.log');

if (!fs.existsSync(LOG_DIR)) {
    fs.mkdirSync(LOG_DIR, { recursive: true });
}

const logStream = fs.createWriteStream(LOG_FILE, { flags: 'a' });

function log(msg) {
    const timestamp = new Date().toISOString();
    const line = `[${timestamp}] ${msg}`;
    console.log(line);
    logStream.write(line + '\n');
}

let stopRequested = false;
let gatewayProc = null;

function startGateway() {
    log('Starting Hermes Gateway...');
    log(`Python: ${PYTHON}`);
    log(`Script: ${RUN_AGENT}`);

    const proc = spawn(PYTHON, [RUN_AGENT, 'gateway', 'start'], {
        cwd: HERMES_HOME,
        env: { ...process.env, HERMES_HOME: HERMES_HOME },
        detached: false,
    });

    proc.stdout.on('data', (data) => {
        log(`[stdout] ${data.toString().trim()}`);
    });

    proc.stderr.on('data', (data) => {
        log(`[stderr] ${data.toString().trim()}`);
    });

    proc.on('close', (code) => {
        log(`Gateway process exited with code ${code}`);
        if (!stopRequested) {
            log('Gateway stopped. Restarting in 10 seconds...');
            setTimeout(startGateway, 10000);
        }
    });

    proc.on('error', (err) => {
        log(`Failed to start gateway: ${err.message}`);
    });

    return proc;
}

process.on('SIGTERM', () => {
    log('SIGTERM received. Stopping...');
    stopRequested = true;
    if (gatewayProc) gatewayProc.kill('SIGTERM');
    process.exit(0);
});

process.on('SIGINT', () => {
    log('SIGINT received. Stopping...');
    stopRequested = true;
    if (gatewayProc) gatewayProc.kill('SIGINT');
    process.exit(0);
});

const command = process.argv[2];

switch (command) {
    case 'install':
        log('Installing Hermes Gateway service...');
        const binPath = `node "${path.join(__dirname, 'hermes-service.js')}" run`;
        exec(`sc.exe create HermesGateway binPath= "${binPath}" start= auto DisplayName= "Hermes AI Gateway"`, (err, stdout, stderr) => {
            if (err) {
                log(`Install failed: ${stderr || err.message}`);
            } else {
                log('Service installed successfully!');
            }
        });
        break;

    case 'start':
        log('Starting service...');
        exec('sc.exe start HermesGateway', (err, stdout, stderr) => {
            if (err) log(`Start failed: ${stderr || err.message}`);
            else log('Service started!');
        });
        break;

    case 'stop':
        log('Stopping service...');
        stopRequested = true;
        exec('sc.exe stop HermesGateway', (err, stdout, stderr) => {
            if (err) log(`Stop failed: ${stderr || err.message}`);
            else log('Service stopped!');
        });
        break;

    case 'remove':
        log('Removing service...');
        exec('sc.exe delete HermesGateway', (err, stdout, stderr) => {
            if (err) log(`Remove failed: ${stderr || err.message}`);
            else log('Service removed!');
        });
        break;

    case 'run':
        log('Hermes Gateway service starting...');
        gatewayProc = startGateway();
        break;

    default:
        log('Usage: node hermes-service.js [install|start|stop|remove|run]');
        process.exit(1);
}