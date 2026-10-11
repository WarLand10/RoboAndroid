// ============================================================
// ROBO - Arduino Mega 2560 / L293D
// Motor controller with independent movement watchdog
// ============================================================

const uint8_t LEFT_IN1  = 10;
const uint8_t LEFT_IN2  = 11;
const uint8_t RIGHT_IN1 = 12;
const uint8_t RIGHT_IN2 = 13;

// Python stops each movement within 5 seconds. This independent
// watchdog stops the motors if the USB connection or PC app fails.
const unsigned long MOTOR_WATCHDOG_MS = 6000UL;
unsigned long lastMotionCommandMs = 0;
bool motorsActive = false;

void stopMotors() {
  digitalWrite(LEFT_IN1, LOW);
  digitalWrite(LEFT_IN2, LOW);
  digitalWrite(RIGHT_IN1, LOW);
  digitalWrite(RIGHT_IN2, LOW);
  motorsActive = false;
}

void setMotorPins(uint8_t l1, uint8_t l2, uint8_t r1, uint8_t r2) {
  digitalWrite(LEFT_IN1, l1);
  digitalWrite(LEFT_IN2, l2);
  digitalWrite(RIGHT_IN1, r1);
  digitalWrite(RIGHT_IN2, r2);
  lastMotionCommandMs = millis();
  motorsActive = true;
}

void moveForward()  { setMotorPins(HIGH, LOW,  HIGH, LOW); }
void moveBackward() { setMotorPins(LOW, HIGH, LOW, HIGH); }
void turnLeft()     { setMotorPins(LOW, HIGH, HIGH, LOW); }
void turnRight()    { setMotorPins(HIGH, LOW, LOW, HIGH); }

void setup() {
  pinMode(LEFT_IN1, OUTPUT);
  pinMode(LEFT_IN2, OUTPUT);
  pinMode(RIGHT_IN1, OUTPUT);
  pinMode(RIGHT_IN2, OUTPUT);
  stopMotors();
  Serial.begin(9600);
  Serial.setTimeout(50);
}

void loop() {
  if (Serial.available() > 0) {
    String command = Serial.readStringUntil('\n');
    command.trim();
    command.toUpperCase();

    if (command == "FORWARD") {
      moveForward();
    } else if (command == "BACKWARD") {
      moveBackward();
    } else if (command == "LEFT") {
      turnLeft();
    } else if (command == "RIGHT") {
      turnRight();
    } else if (command == "STOP") {
      stopMotors();
    }
  }

  if (motorsActive && (millis() - lastMotionCommandMs >= MOTOR_WATCHDOG_MS)) {
    stopMotors();
  }
}
