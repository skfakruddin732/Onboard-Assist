import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css']
})
export class LoginComponent {
  isRegisterMode = false;
  isLoading = false;
  errorMessage = '';
  showNameError = false;
  showEmailError = false;
  showPasswordError = false;
  emailErrorMessage = '';
  passwordErrorMessage = '';

  loginData = { email: '', password: '' };
  registerData = { name: '', email: '', password: '' };

  private isValidEmail(email: string): boolean {
  const pattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return pattern.test(email);
}

  constructor(private authService: AuthService, private router: Router) {
    if (this.authService.isLoggedIn()) this.router.navigate(['/home']);
  }

  onLogin(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.authService.login(this.loginData).subscribe({
      next: () => this.router.navigate(['/home']),
      error: (err) => {
        this.errorMessage = err.error?.message || 'Invalid email or password';
        this.isLoading = false;
      }
    });
  }

  onRegister(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.authService.register(this.registerData).subscribe({
      next: () => {
  this.isRegisterMode = false;
  this.loginData.email = this.registerData.email;
  this.loginData.password = this.registerData.password;
  this.isLoading = false;
},
      error: (err) => {
        this.errorMessage = err.error?.message || 'Registration failed';
        this.isLoading = false;
      }
    });
  }

  toggleMode(): void {
  this.isRegisterMode = !this.isRegisterMode;

  this.errorMessage = '';

  this.showNameError = false;
  this.showEmailError = false;
  this.showPasswordError = false;
}

  onSubmit(): void {

  this.showNameError = false;
  this.showEmailError = false;
  this.showPasswordError = false;
  this.emailErrorMessage = '';
this.passwordErrorMessage = '';

  if (this.isRegisterMode) {

    if (!this.registerData.name.trim()) {
      this.showNameError = true;
      return;
    }

    if (!this.registerData.email.trim()) {
      this.showEmailError = true;
      this.emailErrorMessage = 'Email is required';
      return;
    }

    if (!this.isValidEmail(this.registerData.email)) {
      this.showEmailError = true;
      this.emailErrorMessage = 'Enter a valid email address';
      return;
    }

    if (!this.registerData.password.trim()) {
      this.showPasswordError = true;
      this.passwordErrorMessage = 'Password is required';
      return;
    }

    if (this.registerData.password.length < 6) {
      this.showPasswordError = true;
      this.passwordErrorMessage =
        'Password must be at least 6 characters';
      return;
    }

    this.onRegister();

  } else {

    if (!this.loginData.email.trim()) {
      this.showEmailError = true;
      this.emailErrorMessage = 'Email is required';
      return;
    }

    if (!this.isValidEmail(this.loginData.email)) {
      this.showEmailError = true;
      this.emailErrorMessage = 'Enter a valid email address';
      return;
    }

    if (!this.loginData.password.trim()) {
      this.showPasswordError = true;
      this.passwordErrorMessage = 'Password is required';
      return;
    }

    this.onLogin();
  }
}
}
